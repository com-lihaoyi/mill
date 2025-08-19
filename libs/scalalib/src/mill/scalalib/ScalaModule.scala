package mill
package scalalib

import mill.util.JarManifest
import mill.api.{BuildCtx, DummyInputStream, ModuleRef, PathRef, Result, Task}
import mill.util.BuildInfo
import mill.util.Jvm
import mill.javalib.api.{CompilationResult, JvmWorkerUtil, Versions}
import mainargs.Flag
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi, ScalaBuildTarget}
import mill.api.daemon.internal.{ScalaModuleApi, ScalaPlatform, internal}
import mill.javalib.dependency.versions.{ValidVersion, Version}
import mill.javalib.{CompileFor, SemanticDbJavaModule}
import mill.javalib.api.internal.{JavaCompilerOptions, ZincCompileMixed, ZincScaladocJar}

// this import requires scala-reflect library to be on the classpath
// it was duplicated to scala3-compiler, but is that too powerful to add as a dependency?
import scala.reflect.internal.util.ScalaClassLoader

import scala.util.Using

/**
 * Core configuration required to compile a single Scala module
 */
trait ScalaModule extends JavaModule with TestModule.ScalaModuleBase
    with ScalaModuleApi { outer =>

  trait ScalaTests extends JavaTests with ScalaModule {
    override def scalaOrganization: T[String] = outer.scalaOrganization()
    override def scalaVersion: T[String] = outer.scalaVersion()
    override def scalacPluginMvnDeps: T[Seq[Dep]] = outer.scalacPluginMvnDeps()
    override def scalacPluginClasspath: T[Seq[PathRef]] = outer.scalacPluginClasspath()
    override def scalacOptions: T[Seq[String]] = outer.scalacOptions()
    override def mandatoryScalacOptions: T[Seq[String]] =
      Task { super.mandatoryScalacOptions() }
  }

  private[mill] override lazy val bspExt = {
    ModuleRef(new mill.scalalib.bsp.BspScalaModule.Wrap(this) {}.internalBspJavaModule)
  }
  private[mill] override lazy val genIdeaInternalExt = {
    ModuleRef(new mill.scalalib.idea.GenIdeaModule.Wrap(this) {}.internalGenIdea)
  }

  /**
   * What Scala organization to use
   *
   * @return
   */
  def scalaOrganization: T[String] = Task {
    if (JvmWorkerUtil.isDotty(scalaVersion()))
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
        if (JvmWorkerUtil.isDotty(scalaVersion()))
          Set("dotty-library", "dotty-compiler")
        else if (JvmWorkerUtil.isScala3(scalaVersion()))
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

  def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
    BoundDep(Lib.depToDependency(dep, scalaVersion(), platformSuffix()), dep.force)
  }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] =
    Task.Anon {
      publish.Artifact.fromDep(
        _: Dep,
        scalaVersion(),
        JvmWorkerUtil.scalaBinaryVersion(scalaVersion()),
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
    Task.log.info(
      s"""Output of scalac version: ${sv}
         |            with options: ${options.mkString(" ")}
         |""".stripMargin
    )

    // Zinc isn't outputting any help with `-help` options, so we ask the compiler directly
    val cp = scalaCompilerClasspath()
    Using.resource(ScalaClassLoader.fromURLs(cp.toSeq.map(_.path.toURL))) { cl =>
      def handleResult(trueIsSuccess: Boolean): PartialFunction[Any, Result[Unit]] = {
        val ok = Result.Success(())
        val fail = Result.Failure("The compiler exited with errors (exit code 1)")

        {
          case true | java.lang.Boolean.TRUE => if (trueIsSuccess) ok else fail
          case false | java.lang.Boolean.FALSE => if (trueIsSuccess) fail else ok
          case null if sv.startsWith("2.") =>
            // Scala 2.11 and earlier return `Unit` and require use to use the result value,
            // which we don't want to implement for just a simple help output of a very old compiler
            Result.Success(())
          case x => Result.Failure(s"Got unexpected return type from the scala compiler: ${x}")
        }
      }

      if (sv.startsWith("2.")) {
        // Scala 2.x
        val mainClass = cl.loadClass("scala.tools.nsc.Main")
        val mainMethod = mainClass.getMethod("process", Seq(classOf[Array[String]])*)
        val exitVal = mainMethod.invoke(null, options.toArray)
        handleResult(true)(exitVal)
      } else {
        // Scala 3.x
        val mainClass = cl.loadClass("dotty.tools.dotc.Main")
        val mainMethod = mainClass.getMethod("process", Seq(classOf[Array[String]])*)
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
  def scalacPluginMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  def scalaDocPluginMvnDeps: T[Seq[Dep]] = Task { scalacPluginMvnDeps() }

  /**
   * Mandatory command-line options to pass to the Scala compiler
   * that shouldn't be removed by overriding `scalacOptions`
   */
  protected def mandatoryScalacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Scalac options to activate the compiler plugins.
   */
  private def enablePluginScalacOptions: T[Seq[String]] = Task {

    val resolvedJars = defaultResolver().classpath(
      scalacPluginMvnDeps().map(_.exclude("*" -> "*"))
    )
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  /**
   * Scalac options to activate the compiler plugins for ScalaDoc generation.
   */
  private def enableScalaDocPluginScalacOptions: T[Seq[String]] = Task {
    val resolvedJars = defaultResolver().classpath(
      scalaDocPluginMvnDeps().map(_.exclude("*" -> "*"))
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
   * In most cases, instead of overriding this task you want to override `scalacOptions` instead.
   */
  def allScalacOptions: T[Seq[String]] = Task {
    mandatoryScalacOptions() ++ enablePluginScalacOptions() ++ scalacOptions()
  }

  /**
   * Options to pass directly into Scaladoc.
   */
  def scalaDocOptions: T[Seq[String]] = Task {
    val defaults =
      if (JvmWorkerUtil.isDottyOrScala3(scalaVersion()))
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
  def scalacPluginClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(scalacPluginMvnDeps())
  }

  /**
   * Classpath of the scaladoc (or dottydoc) tool.
   */
  def scalaDocClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Lib.scalaDocMvnDeps(scalaOrganization(), scalaVersion())
    )
  }

  /**
   * The ivy coordinates of Scala's own standard library
   */
  def scalaDocPluginClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      scalaDocPluginMvnDeps()
    )
  }

  def scalaLibraryMvnDeps: T[Seq[Dep]] = Task {
    Lib.scalaRuntimeMvnDeps(scalaOrganization(), scalaVersion())
  }

  /** Adds the Scala Library is a mandatory dependency. */
  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ scalaLibraryMvnDeps()
  }

  /**
   * Classpath of the Scala Compiler & any compiler plugins
   */
  def scalaCompilerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Lib.scalaCompilerMvnDeps(scalaOrganization(), scalaVersion()) ++
        scalaLibraryMvnDeps()
    )
  }

  // Keep in sync with [[bspCompileClassesPath]]
  override def compile: T[CompilationResult] = Task(persistent = true) {
    val sv = scalaVersion()
    if (sv == "2.12.4") Task.log.warn(
      """Attention: Zinc is known to not work properly for Scala version 2.12.4.
        |You may want to select another version. Upgrading to a more recent Scala version is recommended.
        |For details, see: https://github.com/sbt/zinc/issues/1010""".stripMargin
    )

    val jOpts = JavaCompilerOptions(javacOptions() ++ mandatoryJavacOptions())

    jvmWorker()
      .internalWorker()
      .compileMixed(
        ZincCompileMixed(
          upstreamCompileOutput = upstreamCompileOutput(),
          sources = allSourceFiles().map(_.path),
          compileClasspath = compileClasspath().map(_.path),
          javacOptions = jOpts.compiler,
          scalaVersion = sv,
          scalaOrganization = scalaOrganization(),
          scalacOptions = allScalacOptions(),
          compilerClasspath = scalaCompilerClasspath(),
          scalacPluginClasspath = scalacPluginClasspath(),
          incrementalCompilation = zincIncrementalCompilation(),
          auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
        ),
        javaHome = javaHome().map(_.path),
        javaRuntimeOptions = jOpts.runtime,
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems()
      )
  }

  override def docSources: T[Seq[PathRef]] = Task {
    if (JvmWorkerUtil.isScala3(scalaVersion()) && !JvmWorkerUtil.isScala3Milestone(scalaVersion()))
      Seq(compile().classes)
    else allSources()
  }

  def scalaDocGenerated: T[PathRef] = Task {
    val compileCp = Seq(
      "-classpath",
      compileClasspath()
        .iterator
        .filter(_.path.ext != "pom")
        .map(_.path)
        .mkString(java.io.File.pathSeparator)
    )

    def generateWithZinc(
        options: Seq[String],
        files: Seq[os.Path],
        javadocDir: os.Path
    ): PathRef = {
      if (files.isEmpty) {
        PathRef(javadocDir)
      } else {
        jvmWorker()
          .internalWorker()
          .scaladocJar(
            ZincScaladocJar(
              scalaVersion(),
              scalaOrganization(),
              scalaDocClasspath(),
              scalacPluginClasspath(),
              options ++ compileCp ++ scalaDocOptions() ++ files.map(_.toString())
            ),
            javaHome = javaHome().map(_.path)
          ) match {
          case true => PathRef(javadocDir)
          case false => Task.fail("scaladoc generation failed")
        }
      }
    }

    if (JvmWorkerUtil.isScala3(scalaVersion())) { // scaladoc 3
      val javadocDir = Task.dest / "javadoc"
      os.makeDir.all(javadocDir)

      // Scaladoc 3 allows including static files in documentation, but it only
      // supports one directory. Hence, to allow users to generate files
      // dynamically, we consolidate all files from all `docSources` into one
      // directory.
      val combinedStaticDir = Task.dest / "static"
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

      generateWithZinc(
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
      val javadocDir = Task.dest / "javadoc"
      os.makeDir.all(javadocDir)

      generateWithZinc(
        Seq("-d", javadocDir.toNIO.toString),
        Lib.findSourceFiles(docSources(), Seq("java", "scala")),
        javadocDir
      )
    }

  }

  override def docJar: T[PathRef] = Task {
    os.copy(scalaDocGenerated().path, Task.dest / "docs")
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq(Task.dest / "docs")))
  }

  /**
   * Command-line options to pass to the Scala console
   */
  def consoleScalacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Opens up a Scala console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = Task.Command(exclusive = true) {
    if (!mill.constants.Util.hasConsole()) {
      Task.fail("console needs to be run with the -i/--interactive flag")
    } else {
      val useJavaCp = "-usejavacp"

      Jvm.callProcess(
        mainClass =
          if (JvmWorkerUtil.isDottyOrScala3(scalaVersion()))
            "dotty.tools.repl.Main"
          else
            "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(
          _.path
        ),
        jvmArgs = forkArgs(),
        env = allForkEnv(),
        mainArgs = Seq(useJavaCp) ++ consoleScalacOptions().filterNot(Set(useJavaCp)),
        cwd = forkWorkingDir(),
        stdin = os.Inherit,
        stdout = os.Inherit
      )
      ()
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
      resolvedAmmoniteReplMvnDeps()
  }

  def resolvedAmmoniteReplMvnDeps: T[Seq[PathRef]] = Task {
    millResolver().classpath {
      val scaVersion = scalaVersion()
      val ammVersion = ammoniteVersion()
      if (scaVersion != BuildInfo.scalaVersion && ammVersion == Versions.ammonite) {
        Task.log.info(
          s"""Resolving Ammonite Repl ${ammVersion} for Scala ${scaVersion} ...
             |If you encounter dependency resolution failures, please review/override `def ammoniteVersion` to select a compatible release.""".stripMargin
        )
      }
      val bind = bindDependency()
      Seq(BoundDep(
        coursierDependency.withConfiguration(coursier.core.Configuration.runtime),
        force = false
      )) ++
        Seq(mvn"com.lihaoyi:::ammonite:${ammVersion}").map(bind)
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
    if (Task.log.streams.in == DummyInputStream) {
      Task.fail("repl needs to be run with the -i/--interactive flag")
    } else {
      val mainClass = ammoniteMainClass()
      Task.log.debug(s"Using ammonite main class: ${mainClass}")
      Jvm.callProcess(
        mainClass = mainClass,
        classPath = ammoniteReplClasspath().map(_.path).toVector,
        jvmArgs = forkArgs(),
        env = allForkEnv(),
        mainArgs = replOptions,
        cwd = forkWorkingDir(),
        stdin = os.Inherit,
        stdout = os.Inherit
      )
      ()
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
    else JvmWorkerUtil.scalaBinaryVersion(scalaVersion())
  }

  override def zincAuxiliaryClassFileExtensions: T[Seq[String]] = Task {
    super.zincAuxiliaryClassFileExtensions() ++ (
      if (JvmWorkerUtil.isScala3(scalaVersion())) Seq("tasty")
      else Seq.empty[String]
    )
  }

  override def artifactSuffix: T[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

  /**
   * @param all If `true` , fetches also sources, Ammonite and compiler dependencies.
   */
  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = {
    val ammonite = resolvedAmmoniteReplMvnDeps
    val tasks: Seq[Task[Seq[PathRef]]] =
      if (all.value) Seq(ammonite)
      else Seq()

    Task.Command {
      (
        super.prepareOffline(all)() ++
          // resolve the compile bridge jar
          defaultResolver().classpath(
            scalacPluginMvnDeps()
          ) ++
          defaultResolver().classpath(
            scalaDocPluginMvnDeps()
          ) ++
          (
            jvmWorker().scalaCompilerBridgeJarV2(
              scalaVersion(),
              scalaOrganization(),
              defaultResolver()
            ).fullClasspath
          ) ++
          Task.sequence(tasks)().flatten
      ).distinct
    }
  }

  override def manifest: T[JarManifest] = Task {
    super.manifest().add("Scala-Version" -> scalaVersion())
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(
      BspModuleApi.LanguageId.Java,
      BspModuleApi.LanguageId.Scala
    ),
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
        scalaBinaryVersion = JvmWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platform = ScalaPlatform.JVM,
        jars = scalaCompilerClasspath().map(_.path.toURI.toString).iterator.toSeq,
        jvmBuildTarget = Some(bspJvmBuildTargetTask())
      )
    ))
  }

  override def semanticDbScalaVersion: T[String] = scalaVersion()

  override protected def semanticDbPluginClasspath = Task {
    defaultResolver().classpath(
      scalacPluginMvnDeps() ++ semanticDbPluginMvnDeps()
    )
  }

  override def semanticDbDataDetailed: T[SemanticDbJavaModule.SemanticDbData] =
    Task(persistent = true) {
      val sv = scalaVersion()

      val additionalScalacOptions = if (JvmWorkerUtil.isScala3(sv)) {
        Seq("-Xsemanticdb", s"-sourceroot:${BuildCtx.workspaceRoot}")
      } else {
        Seq("-Yrangepos", s"-P:semanticdb:sourceroot:${BuildCtx.workspaceRoot}")
      }

      val scalacOptions = (
        allScalacOptions() ++
          semanticDbEnablePluginScalacOptions() ++
          additionalScalacOptions
      )
        .filterNot(_ == "-Xfatal-warnings")

      val javacOpts = SemanticDbJavaModule.javacOptionsTask(javacOptions(), semanticDbJavaVersion())

      Task.log.debug(s"effective scalac options: ${scalacOptions}")
      Task.log.debug(s"effective javac options: ${javacOpts}")

      val jOpts = JavaCompilerOptions(javacOpts)

      jvmWorker().internalWorker()
        .compileMixed(
          ZincCompileMixed(
            upstreamCompileOutput = upstreamSemanticDbDatas().map(_.compilationResult),
            sources = allSourceFiles().map(_.path),
            compileClasspath =
              (compileClasspathFor(
                CompileFor.SemanticDb
              )() ++ resolvedSemanticDbJavaPluginMvnDeps()).map(_.path),
            javacOptions = jOpts.compiler,
            scalaVersion = sv,
            scalaOrganization = scalaOrganization(),
            scalacOptions = scalacOptions,
            compilerClasspath = scalaCompilerClasspath(),
            scalacPluginClasspath = semanticDbPluginClasspath(),
            incrementalCompilation = zincIncrementalCompilation(),
            auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
          ),
          javaHome = javaHome().map(_.path),
          javaRuntimeOptions = jOpts.runtime,
          reporter = Task.reporter.apply(hashCode),
          reportCachedProblems = zincReportCachedProblems()
        )
        .map { compilationResult =>
          val semanticDbFiles = BuildCtx.withFilesystemCheckerDisabled {
            SemanticDbJavaModule.copySemanticdbFiles(
              compilationResult.classes.path,
              BuildCtx.workspaceRoot,
              Task.dest / "data",
              SemanticDbJavaModule.workerClasspath().map(_.path),
              allSourceFiles().map(_.path)
            )
          }

          SemanticDbJavaModule.SemanticDbData(compilationResult, semanticDbFiles)
        }
    }

  // binary compatibility forwarder
  override def semanticDbData: T[PathRef] =
    // This is the same as `super.semanticDbData()`, but we can't call it directly
    // because then it generates a forwarder which breaks binary compatibility.
    Task { semanticDbDataDetailed().semanticDbFiles }
}
