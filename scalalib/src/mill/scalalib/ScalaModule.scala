package mill
package scalalib

import mill.api.{DummyInputStream, JarManifest, PathRef, Result, internal}
import mill.main.BuildInfo
import mill.util.{Jvm, Util}
import mill.util.Jvm.createJar
import mill.api.Loose.Agg
import mill.scalalib.api.{CompilationResult, Versions, ZincWorkerUtil}
import mainargs.Flag
import mill.scalalib.bsp.{BspBuildTarget, BspModule, ScalaBuildTarget, ScalaPlatform}
import mill.scalalib.dependency.versions.{ValidVersion, Version}

// this import requires scala-reflect library to be on the classpath
// it was duplicated to scala3-compiler, but is that too powerful to add as a dependency?
import scala.reflect.internal.util.ScalaClassLoader

import scala.util.Using

/**
 * Core configuration required to compile a single Scala compilation target
 */
trait ScalaModule extends JavaModule with TestModule.ScalaModuleBase { outer =>
  @deprecated("use ScalaTests", "0.11.0")
  type ScalaModuleTests = ScalaTests

  trait ScalaTests extends JavaTests with ScalaModule {
    override def scalaOrganization: T[String] = outer.scalaOrganization()
    override def scalaVersion: T[String] = outer.scalaVersion()
    override def scalacPluginIvyDeps: T[Agg[Dep]] = outer.scalacPluginIvyDeps()
    override def scalacPluginClasspath: T[Agg[PathRef]] = outer.scalacPluginClasspath()
    override def scalacOptions: T[Seq[String]] = outer.scalacOptions()
    override def mandatoryScalacOptions: T[Seq[String]] =
      Task { super.mandatoryScalacOptions() }
  }

  /**
   * What Scala organization to use
   *
   * @return
   */
  def scalaOrganization: T[String] = Task {
    if (ZincWorkerUtil.isDotty(scalaVersion()))
      "ch.epfl.lamp"
    else
      "org.scala-lang"
  }

  /**
   * All individual source files fed into the Zinc compiler.
   */
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("scala", "java")).map(PathRef(_))
  }

  /**
   * What version of Scala to use
   */
  def scalaVersion: T[String]

  override def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = Task.Anon {
    super.mapDependencies().andThen { (d: coursier.Dependency) =>
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
  }

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] =
    Task.Anon {
      Lib.depToDependency(_: Dep, scalaVersion(), platformSuffix())
    }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] =
    Task.Anon {
      publish.Artifact.fromDep(
        _: Dep,
        scalaVersion(),
        ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platformSuffix()
      )
    }

  /**
   * Print the scala compile built-in help output.
   * This is equivalent to running `scalac -help`
   *
   * @param args The option to pass to the scala compiler, e.g. "-Xlint:help". Default: "-help"
   */
  def scalacHelp(
      @mainargs.arg(doc =
        """The option to pass to the scala compiler, e.g. "-Xlint:help". Default: "-help""""
      )
      args: String*
  ): Command[Unit] = Task.Command {
    val sv = scalaVersion()

    // TODO: do we need to handle compiler plugins?
    val options: Seq[String] = if (args.isEmpty) Seq("-help") else args
    T.log.info(
      s"""Output of scalac version: ${sv}
         |            with options: ${options.mkString(" ")}
         |""".stripMargin
    )

    // Zinc isn't outputting any help with `-help` options, so we ask the compiler directly
    val cp = scalaCompilerClasspath()
    Using.resource(ScalaClassLoader.fromURLs(cp.toSeq.map(_.path.toNIO.toUri().toURL()))) { cl =>
      def handleResult(trueIsSuccess: Boolean): PartialFunction[Any, Result[Unit]] = {
        val ok = Result.Success(())
        val fail = Result.Failure("The compiler exited with errors (exit code 1)")

        {
          case true | java.lang.Boolean.TRUE => if (trueIsSuccess) ok else fail
          case false | java.lang.Boolean.FALSE => if (trueIsSuccess) fail else ok
          case null if sv.startsWith("2.") =>
            // Scala 2.11 and earlier return `Unit` and require use to use the result value,
            // which we don't want to implement for just a simple help output of an very old compiler
            Result.Success(())
          case x => Result.Failure(s"Got unexpected return type from the scala compiler: ${x}")
        }
      }

      if (sv.startsWith("2.")) {
        // Scala 2.x
        val mainClass = cl.loadClass("scala.tools.nsc.Main")
        val mainMethod = mainClass.getMethod("process", Seq(classOf[Array[String]]): _*)
        val exitVal = mainMethod.invoke(null, options.toArray)
        handleResult(true)(exitVal)
      } else {
        // Scala 3.x
        val mainClass = cl.loadClass("dotty.tools.dotc.Main")
        val mainMethod = mainClass.getMethod("process", Seq(classOf[Array[String]]): _*)
        val resultClass = cl.loadClass("dotty.tools.dotc.reporting.Reporter")
        val hasErrorsMethod = resultClass.getMethod("hasErrors")
        val exitVal = mainMethod.invoke(null, options.toArray)
        exitVal match {
          case r if resultClass.isInstance(r) => handleResult(false)(hasErrorsMethod.invoke(r))
          case x => Result.Failure(s"Got unexpected return type from the scala compiler: ${x}")
        }
      }
    }
  }

  /**
   * Allows you to make use of Scala compiler plugins.
   */
  def scalacPluginIvyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  def scalaDocPluginIvyDeps: T[Agg[Dep]] = Task { scalacPluginIvyDeps() }

  /**
   * Mandatory command-line options to pass to the Scala compiler
   * that shouldn't be removed by overriding `scalacOptions`
   */
  protected def mandatoryScalacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Scalac options to activate the compiler plugins.
   */
  private def enablePluginScalacOptions: T[Seq[String]] = Task {

    val resolvedJars = defaultResolver().resolveDeps(
      scalacPluginIvyDeps().map(_.exclude("*" -> "*"))
    )
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  /**
   * Scalac options to activate the compiler plugins for ScalaDoc generation.
   */
  private def enableScalaDocPluginScalacOptions: T[Seq[String]] = Task {
    val resolvedJars = defaultResolver().resolveDeps(
      scalaDocPluginIvyDeps().map(_.exclude("*" -> "*"))
    )
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  /**
   * Command-line options to pass to the Scala compiler defined by the user.
   * Consumers should use `allScalacOptions` to read them.
   */
  override def scalacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Aggregation of all the options passed to the Scala compiler.
   * In most cases, instead of overriding this Target you want to override `scalacOptions` instead.
   */
  def allScalacOptions: T[Seq[String]] = Task {
    mandatoryScalacOptions() ++ enablePluginScalacOptions() ++ scalacOptions()
  }

  /**
   * Options to pass directly into Scaladoc.
   */
  def scalaDocOptions: T[Seq[String]] = Task {
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
  def scalacPluginClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(scalacPluginIvyDeps())
  }

  /**
   * Classpath of the scaladoc (or dottydoc) tool.
   */
  def scalaDocClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Lib.scalaDocIvyDeps(scalaOrganization(), scalaVersion())
    )
  }

  /**
   * The ivy coordinates of Scala's own standard library
   */
  def scalaDocPluginClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      scalaDocPluginIvyDeps()
    )
  }

  def scalaLibraryIvyDeps: T[Agg[Dep]] = Task {
    Lib.scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion())
  }

  /** Adds the Scala Library is a mandatory dependency. */
  override def mandatoryIvyDeps: T[Agg[Dep]] = Task {
    super.mandatoryIvyDeps() ++ scalaLibraryIvyDeps()
  }

  /**
   * Classpath of the Scala Compiler & any compiler plugins
   */
  def scalaCompilerClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Lib.scalaCompilerIvyDeps(scalaOrganization(), scalaVersion()) ++
        scalaLibraryIvyDeps()
    )
  }

  // Keep in sync with [[bspCompileClassesPath]]
  override def compile: T[CompilationResult] = Task(persistent = true) {
    val sv = scalaVersion()
    if (sv == "2.12.4") T.log.error(
      """Attention: Zinc is known to not work properly for Scala version 2.12.4.
        |You may want to select another version. Upgrading to a more recent Scala version is recommended.
        |For details, see: https://github.com/sbt/zinc/issues/1010""".stripMargin
    )
    zincWorker()
      .worker()
      .compileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = allSourceFiles().map(_.path),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        scalaVersion = sv,
        scalaOrganization = scalaOrganization(),
        scalacOptions = allScalacOptions(),
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = scalacPluginClasspath(),
        reporter = T.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation(),
        auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
      )
  }

  /** the path to the compiled classes without forcing the compilation. */
  @internal
  override def bspCompileClassesPath: T[UnresolvedPath] =
    if (compile.ctx.enclosing == s"${classOf[ScalaModule].getName}#compile") {
      Task {
        T.log.debug(
          s"compile target was not overridden, assuming hard-coded classes directory for target ${compile}"
        )
        UnresolvedPath.DestPath(os.sub / "classes", compile.ctx.segments, compile.ctx.foreign)
      }
    } else {
      Task {
        T.log.debug(
          s"compile target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compile}"
        )
        UnresolvedPath.ResolvedPath(compile().classes.path)
      }
    }

  override def docSources: T[Seq[PathRef]] = Task.Sources {
    if (
      ZincWorkerUtil.isScala3(scalaVersion()) && !ZincWorkerUtil.isScala3Milestone(scalaVersion())
    ) Seq(compile().classes)
    else allSources()
  }

  override def docJar: T[PathRef] = Task {
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
        zincWorker()
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
   * Command-line options to pass to the Scala console
   */
  def consoleScalacOptions: T[Seq[String]] = T(Seq.empty[String])

  /**
   * Opens up a Scala console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = Task.Command(exclusive = true) {
    if (!Util.isInteractive()) {
      Result.Failure("console needs to be run with the -i/--interactive flag")
    } else {
      val useJavaCp = "-usejavacp"

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
        mainArgs = Seq(useJavaCp) ++ consoleScalacOptions().filterNot(Set(useJavaCp)),
        workingDir = forkWorkingDir()
      )
      Result.Success(())
    }
  }

  /**
   * Ammonite's version used in the `repl` command is by default
   * set to the one Mill is built against.
   */
  def ammoniteVersion: T[String] = Task {
    Versions.ammonite
  }

  /**
   * Dependencies that are necessary to run the Ammonite Scala REPL
   */
  def ammoniteReplClasspath: T[Seq[PathRef]] = Task {
    localClasspath() ++
      transitiveLocalClasspath() ++
      unmanagedClasspath() ++
      resolvedAmmoniteReplIvyDeps()
  }

  def resolvedAmmoniteReplIvyDeps = Task {
    defaultResolver().resolveDeps {
      val scaVersion = scalaVersion()
      val ammVersion = ammoniteVersion()
      if (scaVersion != BuildInfo.scalaVersion && ammVersion == Versions.ammonite) {
        T.log.info(
          s"""Resolving Ammonite Repl ${ammVersion} for Scala ${scaVersion} ...
             |If you encounter dependency resolution failures, please review/override `def ammoniteVersion` to select a compatible release.""".stripMargin
        )
      }
      val bind = bindDependency()
      transitiveRunIvyDeps() ++ transitiveIvyDeps() ++
        Agg(ivy"com.lihaoyi:::ammonite:${ammVersion}").map(bind)
    }
  }

  @internal
  private[scalalib] def ammoniteMainClass: Task[String] = Task.Anon {
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
  def repl(replOptions: String*): Command[Unit] = Task.Command(exclusive = true) {
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
  def artifactScalaVersion: T[String] = Task {
    if (crossFullScalaVersion()) scalaVersion()
    else ZincWorkerUtil.scalaBinaryVersion(scalaVersion())
  }

  override def zincAuxiliaryClassFileExtensions: T[Seq[String]] = Task {
    super.zincAuxiliaryClassFileExtensions() ++ (
      if (ZincWorkerUtil.isScala3(scalaVersion())) Seq("tasty")
      else Seq.empty[String]
    )
  }

  override def artifactSuffix: T[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

  /**
   * @param all If `true` , fetches also sources, Ammonite and compiler dependencies.
   */
  override def prepareOffline(all: Flag): Command[Unit] = {
    val ammonite = resolvedAmmoniteReplIvyDeps
    val tasks =
      if (all.value) Seq(ammonite)
      else Seq()

    Task.Command {
      super.prepareOffline(all)()
      // resolve the compile bridge jar
      defaultResolver().resolveDeps(
        scalacPluginIvyDeps()
      )
      defaultResolver().resolveDeps(
        scalaDocPluginIvyDeps()
      )
      zincWorker().scalaCompilerBridgeJar(
        scalaVersion(),
        scalaOrganization(),
        repositoriesTask()
      )
      T.sequence(tasks)()
      ()
    }
  }

  override def manifest: T[JarManifest] = Task {
    super.manifest().add("Scala-Version" -> scalaVersion())
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(BspModule.LanguageId.Java, BspModule.LanguageId.Scala),
    canCompile = true,
    canRun = true
  )

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((
      "scala",
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platform = ScalaPlatform.JVM,
        jars = scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq,
        jvmBuildTarget = Some(bspJvmBuildTarget)
      )
    ))
  }

  override def semanticDbScalaVersion: T[String] = scalaVersion()

  override protected def semanticDbPluginClasspath = Task {
    defaultResolver().resolveDeps(
      scalacPluginIvyDeps() ++ semanticDbPluginIvyDeps()
    )
  }

  override def semanticDbData: T[PathRef] = Task(persistent = true) {
    val sv = scalaVersion()

    val scalacOptions = (
      allScalacOptions() ++
        semanticDbEnablePluginScalacOptions() ++ {
          if (ZincWorkerUtil.isScala3(sv)) {
            Seq("-Xsemanticdb", s"-sourceroot:${T.workspace}")
          } else {
            Seq(
              "-Yrangepos",
              s"-P:semanticdb:sourceroot:${T.workspace}",
              "-Ystop-after:semanticdb-typer"
            )
          }
        }
    )
      .filterNot(_ == "-Xfatal-warnings")

    val javacOpts = SemanticDbJavaModule.javacOptionsTask(javacOptions(), semanticDbJavaVersion())

    T.log.debug(s"effective scalac options: ${scalacOptions}")
    T.log.debug(s"effective javac options: ${javacOpts}")

    zincWorker().worker()
      .compileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = allSourceFiles().map(_.path),
        compileClasspath =
          (compileClasspath() ++ resolvedSemanticDbJavaPluginIvyDeps()).map(_.path),
        javacOptions = javacOpts,
        scalaVersion = sv,
        scalaOrganization = scalaOrganization(),
        scalacOptions = scalacOptions,
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = semanticDbPluginClasspath(),
        reporter = None,
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation(),
        auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
      )
      .map(compileRes =>
        SemanticDbJavaModule.copySemanticdbFiles(
          compileRes.classes.path,
          T.workspace,
          T.dest / "data"
        )
      )
  }
}
