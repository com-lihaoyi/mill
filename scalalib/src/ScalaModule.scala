package mill
package scalalib

import scala.annotation.nowarn
import mill.define.{Command, Sources, Target, Task}
import mill.api.{
  CompileProblemReporter,
  DummyInputStream,
  PathRef,
  Problem,
  ProblemPosition,
  Result,
  Severity,
  internal
}
import mill.modules.Jvm
import mill.modules.Jvm.createJar
import mill.api.Loose.Agg
import mill.scalalib.api.{CompilationResult, ZincWorkerUtil}

import scala.jdk.CollectionConverters._
import mainargs.Flag
import mill.scalalib.bsp.{BspBuildTarget, BspModule, ScalaBuildTarget, ScalaPlatform}
import mill.scalalib.dependency.versions.{ValidVersion, Version}
import os.Path
import upickle.default.{ReadWriter, macroRW}

import java.io.File
import scala.collection.immutable.Queue
import scala.util.Try

/**
 * Core configuration required to compile a single Scala compilation target
 */
trait ScalaModule extends JavaModule { outer =>

  trait ScalaModuleTests extends JavaModuleTests with ScalaModule {
    override def scalaOrganization: T[String] = outer.scalaOrganization()
    override def scalaVersion: T[String] = outer.scalaVersion()
    override def scalacPluginIvyDeps = outer.scalacPluginIvyDeps
    override def scalacPluginClasspath = outer.scalacPluginClasspath
    override def scalacOptions = outer.scalacOptions
    override def mandatoryScalacOptions = outer.mandatoryScalacOptions
  }

  trait Tests extends ScalaModuleTests

  /**
   * What Scala organization to use
   *
   * @return
   */
  def scalaOrganization: T[String] = T {
    if (ZincWorkerUtil.isDotty(scalaVersion()))
      "ch.epfl.lamp"
    else
      "org.scala-lang"
  }

  /**
   * All individual source files fed into the Zinc compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), Seq("scala", "java")).map(PathRef(_))
  }

  /**
   * What version of Scala to use
   */
  def scalaVersion: T[String]

  override def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = T.task {
    d: coursier.Dependency =>
      val artifacts =
        if (ZincWorkerUtil.isDotty(scalaVersion()))
          Set("dotty-library", "dotty-compiler")
        else if (ZincWorkerUtil.isScala3(scalaVersion()))
          Set("scala3-library", "scala3-compiler")
        else
          Set("scala-library", "scala-compiler", "scala-reflect")
      if (!artifacts(d.module.name.value)) d
      else
        d.withModule(
          d.module.withOrganization(
            coursier.Organization(scalaOrganization())
          )
        )
          .withVersion(scalaVersion())
  }

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] =
    T.task {
      Lib.depToDependency(_: Dep, scalaVersion(), platformSuffix())
    }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] =
    T.task {
      publish.Artifact.fromDep(
        _: Dep,
        scalaVersion(),
        ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platformSuffix()
      )
    }

  /**
   * Allows you to make use of Scala compiler plugins.
   */
  def scalacPluginIvyDeps: Target[Agg[Dep]] = T { Agg.empty[Dep] }

  def scalaDocPluginIvyDeps: Target[Agg[Dep]] = T { scalacPluginIvyDeps() }

  /**
   * Mandatory command-line options to pass to the Scala compiler
   * that shouldn't be removed by overriding `scalacOptions`
   */
  protected def mandatoryScalacOptions: Target[Seq[String]] = T { Seq.empty[String] }

  /**
   * Scalac options to activate the compiler plugins.
   */
  private def enablePluginScalacOptions: Target[Seq[String]] = T {
    val resolvedJars = resolveDeps(T.task {
      val bind = bindDependency()
      scalacPluginIvyDeps().map(_.exclude("*" -> "*")).map(bind)
    })()
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  /**
   * Scalac options to activate the compiler plugins for ScalaDoc generation.
   */
  private def enableScalaDocPluginScalacOptions: Target[Seq[String]] = T {
    val resolvedJars = resolveDeps(T.task {
      val bind = bindDependency()
      scalaDocPluginIvyDeps().map(bind).map(_.exclude("*" -> "*"))
    })()
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  /**
   * Command-line options to pass to the Scala compiler defined by the user.
   * Consumers should use `allScalacOptions` to read them.
   */
  def scalacOptions: Target[Seq[String]] = T { Seq.empty[String] }

  /**
   * Aggregation of all the options passed to the Scala compiler.
   * In most cases, instead of overriding this Target you want to override `scalacOptions` instead.
   */
  def allScalacOptions: Target[Seq[String]] = T {
    mandatoryScalacOptions() ++ enablePluginScalacOptions() ++ scalacOptions()
  }

  /**
   * Options to pass directly into Scaladoc.
   */
  def scalaDocOptions: T[Seq[String]] = T {
    val defaults =
      if (ZincWorkerUtil.isDottyOrScala3(scalaVersion()))
        Seq(
          "-project",
          artifactName()
        )
      else Seq()
    mandatoryScalacOptions() ++ enableScalaDocPluginScalacOptions() ++ scalacOptions() ++ defaults
  }

  /**
   * The local classpath of Scala compiler plugins on-disk; you can add
   * additional jars here if you have some copiler plugin that isn't present
   * on maven central
   */
  def scalacPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      val bind = bindDependency()
      scalacPluginIvyDeps().map(bind)
    })()
  }

  /**
   * Classpath of the scaladoc (or dottydoc) tool.
   */
  def scalaDocClasspath: T[Agg[PathRef]] = T {
    resolveDeps(
      T.task {
        val bind = bindDependency()
        Lib.scalaDocIvyDeps(scalaOrganization(), scalaVersion()).map(bind)
      }
    )()
  }

  /**
   * The ivy coordinates of Scala's own standard library
   */
  def scalaDocPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      val bind = bindDependency()
      scalaDocPluginIvyDeps().map(bind)
    })()
  }

  def scalaLibraryIvyDeps: T[Agg[Dep]] = T {
    Lib.scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion())
  }

  /** Adds the Scala Library is a mandatory dependency. */
  override def mandatoryIvyDeps: T[Agg[Dep]] = T {
    super.mandatoryIvyDeps() ++ scalaLibraryIvyDeps()
  }

  /**
   * Classpath of the Scala Compiler & any compiler plugins
   */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T {
    resolveDeps(
      T.task {
        val bind = bindDependency()
        (Lib.scalaCompilerIvyDeps(scalaOrganization(), scalaVersion()) ++
          scalaLibraryIvyDeps()).map(bind)
      }
    )()
  }

  // Keep in sync with [[bspCompileClassesPath]]
  override def compile: T[CompilationResult] = T.persistent {
    val sv = scalaVersion()
    if (sv == "2.12.4") T.log.error(
      """Attention: Zinc is known to not work properly for Scala version 2.12.4.
        |You may want to select another version. Upgrading to a more recent Scala version is recommended.
        |For details, see: https://github.com/sbt/zinc/issues/1010""".stripMargin
    )

    import ScalaModule.{LastCompilation, ProblemImpl}

    val previousProblemFile = T.dest / "zinc-reports.json"
    val lastCompilation = if (os.exists(previousProblemFile)) {
      Try {
        upickle.default.read[LastCompilation](os.read.stream(previousProblemFile))
      }.recover {
        e =>
          T.log.debug(s"Could not read last compilation problems, starting fresh. Cause: ${e}")
          LastCompilation(0, Seq(), Seq())
      }.get
    } else LastCompilation(0, Seq(), Seq())

    val compileIncrement = lastCompilation.increment + 1

    //    case class FileProblems(file: Path, problems: Seq[(String, ProblemImpl)])

    //    val problems = Map[String, FileProblems]
    T.log.debug(s"Previous problems: ${lastCompilation}")

    class RecordingReporter(underlying: Option[CompileProblemReporter])
        extends CompileProblemReporter {
      var visitedFiles: Queue[os.Path] = Queue()
      var problems: Queue[ProblemImpl] = Queue()
      override def start(): Unit = underlying.foreach(_.start())
      override def logError(problem: Problem): Unit = {
        problems = problems.appended(ProblemImpl(compileIncrement, problem))
        underlying.foreach(_.logError(problem))
      }
      override def logWarning(problem: Problem): Unit = {
        problems = problems.appended(ProblemImpl(compileIncrement, problem))
        underlying.foreach(_.logWarning(problem))
      }
      override def logInfo(problem: Problem): Unit = {
        problems = problems.appended(ProblemImpl(compileIncrement, problem))
        underlying.foreach(_.logInfo(problem))
      }
      override def fileVisited(file: os.Path): Unit = {
        visitedFiles = visitedFiles.appended(file)
        underlying.foreach(_.fileVisited(file))
      }
      override def printSummary(): Unit = underlying.foreach(_.printSummary())
      override def finish(): Unit = underlying.foreach(_.finish())
    }

    val reporter = new RecordingReporter(T.reporter.apply(hashCode))

    val result = zincWorker
      .worker()
      .compileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = allSourceFiles().map(_.path),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = javacOptions(),
        scalaVersion = sv,
        scalaOrganization = scalaOrganization(),
        scalacOptions = allScalacOptions(),
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = scalacPluginClasspath(),
        reporter = Some(reporter)
      )

    val newVisitedFiles = reporter.visitedFiles.toSeq.distinct

    val lastProblemCount = lastCompilation.lastProblems.size
    // filter all problems, which are released to files we re-visited
    val cleanedCachedProblems = lastCompilation.lastProblems.toSeq
      .filter(p =>
        p.position.sourceFile.isEmpty || !newVisitedFiles.contains(
          os.Path(p.position.sourceFile.get)
        )
      )

    T.log.debug(
      s"${cleanedCachedProblems.size} of ${lastProblemCount} cached problems from previous compilation: ${cleanedCachedProblems}"
    )

    T.log.debug(s"${newVisitedFiles.size} recorded visited files: ${newVisitedFiles}")
    T.log.debug(s"${reporter.problems.size} recorded new file problems: ${reporter.problems.toSeq}")
    // TODO: show those recorded problems

    if (cleanedCachedProblems.nonEmpty) {
      val reportStream = T.log.errorStream
      reportStream.println("Previous compile problems:")
      cleanedCachedProblems.foreach { p =>
        reportStream.println(p.formatted)
      }

    }

    val curCompilation = LastCompilation(
      compileIncrement,
      lastVisitedFiles = lastCompilation.lastVisitedFiles ++ reporter.visitedFiles,
      lastProblems = cleanedCachedProblems ++ reporter.problems
    )

    os.write.over(previousProblemFile, upickle.default.write(curCompilation, 2))

    result
  }

  /** the path to the compiled classes without forcing the compilation. */
  @internal
  override def bspCompileClassesPath: Target[UnresolvedPath] =
    if (compile.ctx.enclosing == s"${classOf[ScalaModule].getName}#compile") {
      T {
        T.log.debug(
          s"compile target was not overridden, assuming hard-coded classes directory for target ${compile}"
        )
        UnresolvedPath.DestPath(os.sub / "classes", compile.ctx.segments, compile.ctx.foreign)
      }
    } else {
      T {
        T.log.debug(
          s"compile target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compile}"
        )
        UnresolvedPath.ResolvedPath(compile().classes.path)
      }
    }

  override def docSources: Sources = T.sources {
    if (
      ZincWorkerUtil.isScala3(scalaVersion()) && !ZincWorkerUtil.isScala3Milestone(scalaVersion())
    ) Seq(compile().classes)
    else allSources()
  }

  override def docJar: T[PathRef] = T {
    val compileCp = Seq(
      "-classpath",
      compileClasspath()
        .iterator
        .filter(_.path.ext != "pom")
        .map(_.path)
        .mkString(java.io.File.pathSeparator)
    )

    def packageWithZinc(options: Seq[String], files: Seq[os.Path], javadocDir: os.Path) = {
      if (files.isEmpty) Result.Success(createJar(Agg(javadocDir))(T.dest))
      else {
        zincWorker
          .worker()
          .docJar(
            scalaVersion(),
            scalaOrganization(),
            scalaDocClasspath(),
            scalacPluginClasspath(),
            options ++ compileCp ++ scalaDocOptions() ++
              files.map(_.toString())
          ) match {
          case true => Result.Success(createJar(Agg(javadocDir))(T.dest))
          case false => Result.Failure("docJar generation failed")
        }
      }
    }

    if (
      ZincWorkerUtil.isDotty(scalaVersion()) || ZincWorkerUtil.isScala3Milestone(scalaVersion())
    ) { // dottydoc
      val javadocDir = T.dest / "javadoc"
      os.makeDir.all(javadocDir)

      for {
        ref <- docResources()
        docResource = ref.path
        if os.exists(docResource) && os.isDir(docResource)
        children = os.walk(docResource)
        child <- children
        if os.isFile(child) && !child.last.startsWith(".")
      } {
        os.copy.over(
          child,
          javadocDir / (child.subRelativeTo(docResource)),
          createFolders = true
        )
      }
      packageWithZinc(
        Seq("-siteroot", javadocDir.toNIO.toString),
        Lib.findSourceFiles(docSources(), Seq("java", "scala")),
        javadocDir / "_site"
      )

    } else if (ZincWorkerUtil.isScala3(scalaVersion())) { // scaladoc 3
      val javadocDir = T.dest / "javadoc"
      os.makeDir.all(javadocDir)

      // Scaladoc 3 allows including static files in documentation, but it only
      // supports one directory. Hence, to allow users to generate files
      // dynamically, we consolidate all files from all `docSources` into one
      // directory.
      val combinedStaticDir = T.dest / "static"
      os.makeDir.all(combinedStaticDir)

      for {
        ref <- docResources()
        docResource = ref.path
        if os.exists(docResource) && os.isDir(docResource)
        children = os.walk(docResource)
        child <- children
        if os.isFile(child) && !child.last.startsWith(".")
      } {
        os.copy.over(
          child,
          combinedStaticDir / child.subRelativeTo(docResource),
          createFolders = true
        )
      }

      packageWithZinc(
        Seq(
          "-d",
          javadocDir.toNIO.toString,
          "-siteroot",
          combinedStaticDir.toNIO.toString
        ),
        Lib.findSourceFiles(docSources(), Seq("tasty")),
        javadocDir
      )
    } else { // scaladoc 2
      val javadocDir = T.dest / "javadoc"
      os.makeDir.all(javadocDir)

      packageWithZinc(
        Seq("-d", javadocDir.toNIO.toString),
        Lib.findSourceFiles(docSources(), Seq("java", "scala")),
        javadocDir
      )
    }

  }

  /**
   * Opens up a Scala console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = T.command {
    if (T.log.inStream == DummyInputStream) {
      Result.Failure("console needs to be run with the -i/--interactive flag")
    } else {
      Jvm.runSubprocess(
        mainClass =
          if (ZincWorkerUtil.isDottyOrScala3(scalaVersion()))
            "dotty.tools.repl.Main"
          else
            "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(
          _.path
        ),
        jvmArgs = forkArgs(),
        envArgs = forkEnv(),
        mainArgs = Seq("-usejavacp"),
        workingDir = forkWorkingDir()
      )
      Result.Success(())
    }
  }

  /**
   * Ammonite's version used in the `repl` command is by default
   * set to the one Mill is built against.
   */
  def ammoniteVersion: T[String] = T {
    Versions.ammonite
  }

  /**
   * Dependencies that are necessary to run the Ammonite Scala REPL
   */
  def ammoniteReplClasspath: T[Seq[PathRef]] = T {
    localClasspath() ++
      transitiveLocalClasspath() ++
      unmanagedClasspath() ++
      resolvedAmmoniteReplIvyDeps()
  }

  def resolvedAmmoniteReplIvyDeps = T {
    resolveDeps(T.task {
      val scaVersion = scalaVersion()
      val ammVersion = ammoniteVersion()
      if (scaVersion != BuildInfo.scalaVersion && ammVersion == Versions.ammonite) {
        T.log.info(
          s"""Resolving Ammonite Repl ${ammVersion} for Scala ${scaVersion} ...
             |If you encounter dependency resolution failures, please review/override `def ammoniteVersion` to select a compatible release.""".stripMargin
        )
      }
      val bind = bindDependency()
      runIvyDeps().map(bind) ++ transitiveIvyDeps() ++
        Agg(ivy"com.lihaoyi:::ammonite:${ammVersion}").map(bind)
    })()
  }

  @internal
  private[scalalib] def ammoniteMainClass: Task[String] = T.task {
    Version(ammoniteVersion()) match {
      case v: ValidVersion if Version.versionOrdering.compare(v, Version("2.4.1")) <= 0 =>
        "ammonite.Main"
      case _ => "ammonite.AmmoniteMain"
    }
  }

  /**
   * Opens up an Ammonite Scala REPL with your module and all dependencies present,
   * for you to test and operate your code interactively.
   * Use [[ammoniteVersion]] to customize the Ammonite version to use.
   */
  def repl(replOptions: String*): Command[Unit] = T.command {
    if (T.log.inStream == DummyInputStream) {
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    } else {
      val mainClass = ammoniteMainClass()
      T.log.debug(s"Using ammonite main class: ${mainClass}")
      Jvm.runSubprocess(
        mainClass = mainClass,
        classPath = ammoniteReplClasspath().map(_.path),
        jvmArgs = forkArgs(),
        envArgs = forkEnv(),
        mainArgs = replOptions,
        workingDir = forkWorkingDir()
      )
      Result.Success(())
    }

  }

  /**
   * Whether to publish artifacts with name "mill_2.12.4" instead of "mill_2.12"
   */
  def crossFullScalaVersion: T[Boolean] = false

  /**
   * What Scala version string to use when publishing
   */
  def artifactScalaVersion: T[String] = T {
    if (crossFullScalaVersion()) scalaVersion()
    else ZincWorkerUtil.scalaBinaryVersion(scalaVersion())
  }

  override def artifactSuffix: T[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

  /**
   * @param all If `true` , fetches also sources, Ammonite and compiler dependencies.
   */
  @nowarn("msg=pure expression does nothing")
  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(
        resolvedAmmoniteReplIvyDeps,
        T.task {
          zincWorker.scalaCompilerBridgeJar(scalaVersion(), scalaOrganization(), repositoriesTask())
        }
      )
      else Seq()

    T.command {
      super.prepareOffline(all)()
      resolveDeps(T.task {
        val bind = bindDependency()
        scalacPluginIvyDeps().map(bind)
      })()
      resolveDeps(T.task {
        val bind = bindDependency()
        scalaDocPluginIvyDeps().map(bind)
      })()
      T.sequence(tasks)()
      ()
    }
  }

  override def manifest: T[Jvm.JarManifest] = T {
    super.manifest().add("Scala-Version" -> scalaVersion())
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(BspModule.LanguageId.Java, BspModule.LanguageId.Scala),
    canCompile = true,
    canRun = true
  )

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
    Some((
      "scala",
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platform = ScalaPlatform.JVM,
        jars = scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq,
        jvmBuildTarget = None
      )
    ))
  }

}

object ScalaModule {

  case class LastCompilation(
      increment: Int,
      lastVisitedFiles: Seq[os.Path],
      lastProblems: Seq[ProblemImpl]
  )

  object LastCompilation {
    //    implicit val pathJsonRW: ReadWriter[os.Path] = mill.api.JsonFormatters.pathReadWrite
//    implicit val pathJsonRW: ReadWriter[os.Path] = upickle.default.readwriter[String].bimap[os.Path](
//      _.toString(),
//      os.Path(_)
//    )
    implicit def jsonRW: ReadWriter[LastCompilation] = macroRW
  }

  case class ProblemImpl(
      increment: Int,
      override val category: String,
      override val severity: Severity,
      override val message: String,
      override val position: ProblemPositionImpl
  ) extends Problem {
    def formatted: String = {
      // TODO: colors
      s"[${severity match {
          case mill.api.Info => "info"
          case mill.api.Warn => "warn"
          case mill.api.Error => "error"
        }}] ${position.sourceFile.getOrElse("")}:${position.line.getOrElse("")}:${position.offset.getOrElse("")}: ${message}"
    }
  }

  object ProblemImpl {
    def apply(increment: Int, other: Problem): ProblemImpl =
      ProblemImpl(
        increment = increment,
        category = other.category,
        severity = other.severity,
        message = other.message,
        position = ProblemPositionImpl(other.position)
      )

    implicit val jsonRW: ReadWriter[ProblemImpl] = macroRW
    implicit val severityJsonRW: ReadWriter[Severity] = upickle.default.readwriter[String].bimap(
      {
        case mill.api.Info => "Info"
        case mill.api.Warn => "Warn"
        case mill.api.Error => "Error"
      },
      {
        case "Info" => mill.api.Info
        case "Warn" => mill.api.Warn
        case "Error" => mill.api.Error
      }
    )

  }

  case class ProblemPositionImpl(
      override val line: Option[Int],
      override val lineContent: String,
      override val offset: Option[Int],
      override val pointer: Option[Int],
      override val pointerSpace: Option[String],
      override val sourcePath: Option[String],
      override val sourceFile: Option[File],
      override val startOffset: Option[Int],
      override val endOffset: Option[Int],
      override val startLine: Option[Int],
      override val startColumn: Option[Int],
      override val endLine: Option[Int],
      override val endColumn: Option[Int]
  ) extends ProblemPosition

  object ProblemPositionImpl {
    def apply(other: ProblemPosition): ProblemPositionImpl = ProblemPositionImpl(
      line = other.line,
      lineContent = other.lineContent,
      offset = other.offset,
      pointer = other.pointer,
      pointerSpace = other.pointerSpace,
      sourcePath = other.sourcePath,
      sourceFile = other.sourceFile,
      startOffset = other.startOffset,
      endOffset = other.endOffset,
      startLine = other.startLine,
      startColumn = other.startColumn,
      endLine = other.endLine,
      endColumn = other.endColumn
    )
    implicit val jsonRW: ReadWriter[ProblemPositionImpl] = macroRW
    implicit val fileJsonRW: ReadWriter[File] = upickle.default.readwriter[String].bimap(
      _.toString(),
      new File(_)
    )
  }

}
