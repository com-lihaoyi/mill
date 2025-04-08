package mill
package scalajslib

import mainargs.{Flag, arg}
import mill.api.{PathRef, Result, internal}
import mill.scalalib.api.JvmWorkerUtil
import mill.scalalib.{CrossVersion, Dep, DepSyntax, Lib, TestModule}
import mill.testrunner.{TestResult, TestRunner, TestRunnerUtils}
import mill.define.{Command, Task}
import mill.scalajslib.api.*
import mill.scalajslib.worker.{ScalaJSWorker, ScalaJSWorkerExternalModule}
import mill.scalalib.bsp.{ScalaBuildTarget, ScalaPlatform}
import mill.T

trait ScalaJSModule extends scalalib.ScalaModule with mill.runner.api.ScalaJSModuleApi { outer =>

  def scalaJSVersion: T[String]

  trait ScalaJSTests extends ScalaTests with TestScalaJSModule {
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleKind: T[ModuleKind] = outer.moduleKind()
    override def moduleSplitStyle: T[ModuleSplitStyle] = outer.moduleSplitStyle()
    override def esFeatures = outer.esFeatures()
    override def jsEnvConfig: T[JsEnvConfig] = outer.jsEnvConfig()
    override def scalaJSOptimizer: T[Boolean] = outer.scalaJSOptimizer()
  }

  def scalaJSBinaryVersion = Task { JvmWorkerUtil.scalaJSBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = Task { JvmWorkerUtil.scalaJSWorkerVersion(scalaJSVersion()) }

  override def scalaLibraryIvyDeps: T[Seq[Dep]] = Task {
    val deps = super.scalaLibraryIvyDeps()
    if (JvmWorkerUtil.isScala3(scalaVersion())) {
      // Since Dotty/Scala3, Scala.JS is published with a platform suffix
      deps.map(dep =>
        dep.copy(cross = dep.cross match {
          case c: CrossVersion.Constant => c.copy(platformed = true)
          case c: CrossVersion.Binary => c.copy(platformed = true)
          case c: CrossVersion.Full => c.copy(platformed = true)
        })
      )
    } else deps
  }

  def scalaJSWorkerClasspath = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule(s"mill-scalajslib-worker-${scalaJSWorkerVersion()}")
    ))
  }

  def scalaJSJsEnvIvyDeps: T[Seq[Dep]] = Task {
    val dep = jsEnvConfig() match {
      case _: JsEnvConfig.NodeJs =>
        ivy"${ScalaJSBuildInfo.scalajsEnvNodejs}"
      case _: JsEnvConfig.JsDom =>
        ivy"${ScalaJSBuildInfo.scalajsEnvJsdomNodejs}"
      case _: JsEnvConfig.ExoegoJsDomNodeJs =>
        ivy"${ScalaJSBuildInfo.scalajsEnvExoegoJsdomNodejs}"
      case _: JsEnvConfig.Phantom =>
        ivy"${ScalaJSBuildInfo.scalajsEnvPhantomJs}"
      case _: JsEnvConfig.Selenium =>
        ivy"${ScalaJSBuildInfo.scalajsEnvSelenium}"
    }

    Seq(dep)
  }

  def scalaJSLinkerClasspath: T[Seq[PathRef]] = Task {
    val commonDeps = Seq(
      ivy"org.scala-js:scalajs-sbt-test-adapter_2.13:${scalaJSVersion()}"
    )
    val scalajsImportMapDeps = scalaJSVersion() match {
      case s"1.$n.$_" if n.toIntOption.exists(_ >= 16) && scalaJSImportMap().nonEmpty =>
        Seq(ivy"${ScalaJSBuildInfo.scalajsImportMap}")
      case _ => Seq.empty[Dep]
    }

    val envDeps = scalaJSBinaryVersion() match {
      case "0.6" =>
        Seq(
          ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}",
          ivy"org.scala-js::scalajs-js-envs:${scalaJSVersion()}"
        )
      case "1" =>
        Seq(
          ivy"org.scala-js:scalajs-linker_2.13:${scalaJSVersion()}"
        ) ++ scalaJSJsEnvIvyDeps()
    }
    // we need to use the scala-library of the currently running mill
    defaultResolver().classpath(
      (commonDeps.iterator ++ envDeps ++ scalajsImportMapDeps)
        .map(Lib.depToBoundDep(_, mill.util.BuildInfo.scalaVersion, ""))
    )
  }

  def scalaJSToolsClasspath = Task { scalaJSWorkerClasspath() ++ scalaJSLinkerClasspath() }

  def fastLinkJS: T[Report] = Task(persistent = true) {
    linkTask(isFullLinkJS = false, forceOutJs = false)()
  }

  def fullLinkJS: T[Report] = Task(persistent = true) {
    linkTask(isFullLinkJS = true, forceOutJs = false)()
  }

  private def linkTask(isFullLinkJS: Boolean, forceOutJs: Boolean): Task[Report] = Task.Anon {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = runClasspath(),
      mainClass = Result.fromEither(finalMainClassOpt()),
      forceOutJs = forceOutJs,
      testBridgeInit = false,
      isFullLinkJS = isFullLinkJS,
      optimizer = scalaJSOptimizer(),
      sourceMap = scalaJSSourceMap(),
      moduleKind = moduleKind(),
      esFeatures = esFeatures(),
      moduleSplitStyle = moduleSplitStyle(),
      outputPatterns = scalaJSOutputPatterns(),
      minify = scalaJSMinify(),
      importMap = scalaJSImportMap(),
      experimentalUseWebAssembly = scalaJSExperimentalUseWebAssembly()
    )
  }

  override def runLocal(args: Task[Args] = Task.Anon(Args())): Command[Unit] =
    Task.Command { run(args)() }

  override def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    if (args().value.nonEmpty) {
      Task.log.warn("Passing command line arguments to run is not supported by Scala.js.")
    }
    finalMainClassOpt() match {
      case Left(err) => Result.Failure(err)
      case Right(_) =>
        ScalaJSWorkerExternalModule.scalaJSWorker().run(
          scalaJSToolsClasspath(),
          jsEnvConfig(),
          fastLinkJS()
        )
        Result.Success(())
    }

  }

  override def runMainLocal(
      @arg(positional = true) mainClass: String,
      args: String*
  ): Command[Unit] = Task.Command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  override def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] =
    Task.Command[Unit] {
      mill.api.Result.Failure("runMain is not supported in Scala.js")
    }

  private[scalajslib] def linkJs(
      worker: ScalaJSWorker,
      toolsClasspath: Seq[PathRef],
      runClasspath: Seq[PathRef],
      mainClass: Result[String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      minify: Boolean,
      importMap: Seq[ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  )(implicit ctx: mill.api.Ctx): Result[Report] = {
    val outputPath = ctx.dest

    os.makeDir.all(ctx.dest)

    worker.link(
      toolsClasspath = toolsClasspath,
      runClasspath = runClasspath,
      dest = outputPath.toIO,
      main = mainClass,
      forceOutJs = forceOutJs,
      testBridgeInit = testBridgeInit,
      isFullLinkJS = isFullLinkJS,
      optimizer = optimizer,
      sourceMap = sourceMap,
      moduleKind = moduleKind,
      esFeatures = esFeatures,
      moduleSplitStyle = moduleSplitStyle,
      outputPatterns = outputPatterns,
      minify = minify,
      importMap = importMap,
      experimentalUseWebAssembly = experimentalUseWebAssembly
    )
  }

  override def mandatoryScalacOptions: T[Seq[String]] = Task {
    // Don't add flag twice, e.g. if a test suite inherits it both directly
    // ScalaJSModule as well as from the enclosing non-test ScalaJSModule
    val scalajsFlag =
      if (
        JvmWorkerUtil.isScala3(scalaVersion()) &&
        !super.mandatoryScalacOptions().contains("-scalajs")
      ) Seq("-scalajs")
      else Seq.empty

    super.mandatoryScalacOptions() ++ scalajsFlag
  }

  override def scalacPluginIvyDeps = Task {
    super.scalacPluginIvyDeps() ++ {
      if (JvmWorkerUtil.isScala3(scalaVersion())) {
        Seq.empty
      } else {
        Seq(ivy"org.scala-js:::scalajs-compiler:${scalaJSVersion()}")
      }
    }
  }

  /** Adds the Scala.js Library as mandatory dependency. */
  override def mandatoryIvyDeps = Task {
    val prev = super.mandatoryIvyDeps()
    val scalaVer = scalaVersion()
    val scalaJSVer = scalaJSVersion()

    val scalaJSLibrary =
      ivy"org.scala-js::scalajs-library:$scalaJSVer".withDottyCompat(scalaVer)

    /* For Scala 2.x and Scala.js >= 1.15.0, explicitly add scalajs-scalalib,
     * in order to support forward binary incompatible changes in the standard library.
     */
    if (
      scalaVer.startsWith("2.") && scalaJSVer.startsWith("1.")
      && scalaJSVer.drop(2).takeWhile(_.isDigit).toInt >= 15
    ) {
      val scalaJSScalalib = ivy"org.scala-js::scalajs-scalalib:$scalaVer+$scalaJSVer"
      prev ++ Seq(scalaJSLibrary, scalaJSScalalib)
    } else {
      prev ++ Seq(scalaJSLibrary)
    }
  }

  // publish artifact with name "mill_sjs0.6.4_2.12" instead of "mill_sjs0.6_2.12"
  def crossFullScalaJSVersion: T[Boolean] = false
  def artifactScalaJSVersion: T[String] = Task {
    if (crossFullScalaJSVersion()) scalaJSVersion()
    else scalaJSBinaryVersion()
  }

  override def platformSuffix: T[String] = s"_sjs${artifactScalaJSVersion()}"

  def jsEnvConfig: T[JsEnvConfig] = Task { JsEnvConfig.NodeJs() }

  def moduleKind: T[ModuleKind] = Task { ModuleKind.NoModule }

  def esFeatures: T[ESFeatures] = Task {
    if (scalaJSVersion().startsWith("0."))
      ESFeatures.Defaults.withESVersion(ESVersion.ES5_1)
    else
      ESFeatures.Defaults
  }

  def moduleSplitStyle: T[ModuleSplitStyle] = Task { ModuleSplitStyle.FewestModules }

  def scalaJSOptimizer: T[Boolean] = Task { true }

  def scalaJSImportMap: T[Seq[ESModuleImportMapping]] = Task {
    Seq.empty[ESModuleImportMapping]
  }

  /** Whether to emit a source map. */
  def scalaJSSourceMap: T[Boolean] = Task { true }

  /**
   * Specifies whether to use the experimental WebAssembly backend. Requires scalaJS > 1.17.0
   *  When using this setting, the following properties must also hold:
   *
   *  - `moduleKind = ModuleKind.ESModule`
   *  - `moduleSplitStyle = ModuleSplitStyle.FewestModules`
   *
   *  @note
   *    Currently, the WebAssembly backend silently ignores `@JSExport` and
   *    `@JSExportAll` annotations. This behavior may change in the future,
   *    either by making them warnings or errors, or by adding support for them.
   *    All other language features are supported.
   */
  def scalaJSExperimentalUseWebAssembly: T[Boolean] = Task { false }

  /** Name patterns for output. */
  def scalaJSOutputPatterns: T[OutputPatterns] = Task { OutputPatterns.Defaults }

  /**
   * Apply Scala.js-specific minification of the produced .js files in fullLinkJS.
   *
   *  When enabled, the linker more aggressively reduces the size of the
   *  generated code, at the cost of readability and debuggability. It does
   *  not perform size optimizations that would negatively impact run-time
   *  performance.
   *
   *  The focus is on optimizations that general-purpose JavaScript minifiers
   *  cannot do on their own. For the best results, we expect the Scala.js
   *  minifier to be used in conjunction with a general-purpose JavaScript
   *  minifier.
   */
  def scalaJSMinify: T[Boolean] = Task { true }

  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(scalaJSToolsClasspath)
      else Seq()
    Task.Command {
      super.prepareOffline(all)()
      Task.sequence(tasks)()
      ()
    }
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((
      ScalaBuildTarget.dataKind,
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = JvmWorkerUtil.scalaBinaryVersion(scalaVersion()),
        platform = ScalaPlatform.JS,
        jars = scalaCompilerClasspath().iterator.map(_.path.toNIO.toUri.toString).toSeq,
        jvmBuildTarget = None
      )
    ))
  }

  override def zincAuxiliaryClassFileExtensions: T[Seq[String]] =
    super.zincAuxiliaryClassFileExtensions() :+ "sjsir"

}

trait TestScalaJSModule extends ScalaJSModule with TestModule {
  override def resources: T[Seq[PathRef]] = super[ScalaJSModule].resources
  def scalaJSTestDeps = Task {
    defaultResolver().classpath(
      Seq(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-bridge:${scalaJSVersion()}"
      )
        .map(_.withDottyCompat(scalaVersion()))
    )
  }

  def fastLinkJSTest: T[Report] = Task(persistent = true) {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      mainClass = Result.Failure("No main class specified or found"),
      forceOutJs = false,
      testBridgeInit = true,
      isFullLinkJS = false,
      optimizer = scalaJSOptimizer(),
      sourceMap = scalaJSSourceMap(),
      moduleKind = moduleKind(),
      esFeatures = esFeatures(),
      moduleSplitStyle = moduleSplitStyle(),
      outputPatterns = scalaJSOutputPatterns(),
      minify = scalaJSMinify(),
      importMap = scalaJSImportMap(),
      experimentalUseWebAssembly = scalaJSExperimentalUseWebAssembly()
    )
  }

  override def testLocal(args: String*): Command[(String, Seq[TestResult])] =
    Task.Command { testForked(args*)() }

  override protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[TestResult])] = Task.Anon {

    val (close, framework) = ScalaJSWorkerExternalModule.scalaJSWorker().getFramework(
      scalaJSToolsClasspath(),
      jsEnvConfig(),
      testFramework(),
      fastLinkJSTest()
    )

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Seq(compile().classes.path),
      args(),
      Task.testReporter,
      cls => TestRunnerUtils.globFilter(globSelectors())(cls.getName)
    )
    val res = TestModule.handleResults(doneMsg, results, Task.ctx(), testReportXml())
    // Hack to try and let the Node.js subprocess finish streaming its stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }

}
