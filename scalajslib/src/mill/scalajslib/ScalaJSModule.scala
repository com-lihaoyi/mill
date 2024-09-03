package mill
package scalajslib

import mainargs.{Flag, arg}
import mill.api.{Loose, PathRef, Result, internal}
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{CrossVersion, Dep, DepSyntax, Lib, TestModule}
import mill.testrunner.{TestResult, TestRunner, TestRunnerUtils}
import mill.define.{Command, Target, Task}
import mill.scalajslib.api._
import mill.scalajslib.internal.ScalaJSUtils.getReportMainFilePathRef
import mill.scalajslib.worker.{ScalaJSWorker, ScalaJSWorkerExternalModule}
import mill.scalalib.bsp.{ScalaBuildTarget, ScalaPlatform}
import mill.T

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  trait ScalaJSTests extends ScalaTests with TestScalaJSModule {
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleKind: Target[ModuleKind] = outer.moduleKind()
    override def moduleSplitStyle: Target[ModuleSplitStyle] = outer.moduleSplitStyle()
    override def esFeatures = outer.esFeatures()
    override def jsEnvConfig: Target[JsEnvConfig] = outer.jsEnvConfig()
    override def scalaJSOptimizer: Target[Boolean] = outer.scalaJSOptimizer()
  }
  @deprecated("use ScalaJSTests", "0.11.0")
  type ScalaJSModuleTests = ScalaJSTests
  @deprecated("use ScalaJSTests", "0.11.0")
  trait Tests extends ScalaJSTests

  def scalaJSBinaryVersion = T { ZincWorkerUtil.scalaJSBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = T { ZincWorkerUtil.scalaJSWorkerVersion(scalaJSVersion()) }

  override def scalaLibraryIvyDeps = T {
    val deps = super.scalaLibraryIvyDeps()
    if (ZincWorkerUtil.isScala3(scalaVersion())) {
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

  def scalaJSWorkerClasspath = T {
    mill.util.Util.millProjectModule(
      artifact = s"mill-scalajslib-worker-${scalaJSWorkerVersion()}",
      repositories = repositoriesTask(),
      resolveFilter = _.toString.contains("mill-scalajslib-worker")
    )
  }

  def scalaJSJsEnvIvyDeps: Target[Agg[Dep]] = T {
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

    Agg(dep)
  }

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T {
    val commonDeps = Seq(
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJSVersion()}"
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
          ivy"org.scala-js::scalajs-linker:${scalaJSVersion()}"
        ) ++ scalaJSJsEnvIvyDeps()
    }
    // we need to use the scala-library of the currently running mill
    resolveDependencies(
      repositoriesTask(),
      (commonDeps.iterator ++ envDeps ++ scalajsImportMapDeps)
        .map(Lib.depToBoundDep(_, mill.main.BuildInfo.scalaVersion, "")),
      ctx = Some(T.log)
    )
  }

  def scalaJSToolsClasspath = T { scalaJSWorkerClasspath() ++ scalaJSLinkerClasspath() }

  def fastLinkJS: Target[Report] = T.persistent {
    linkTask(isFullLinkJS = false, forceOutJs = false)()
  }

  def fullLinkJS: Target[Report] = T.persistent {
    linkTask(isFullLinkJS = true, forceOutJs = false)()
  }

  @deprecated("Use fastLinkJS instead", "Mill 0.10.12")
  def fastOpt: Target[PathRef] = T {
    getReportMainFilePathRef(linkTask(isFullLinkJS = false, forceOutJs = true)())
  }

  @deprecated("Use fullLinkJS instead", "Mill 0.10.12")
  def fullOpt: Target[PathRef] = T {
    getReportMainFilePathRef(linkTask(isFullLinkJS = true, forceOutJs = true)())
  }

  private def linkTask(isFullLinkJS: Boolean, forceOutJs: Boolean): Task[Report] = T.task {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = runClasspath(),
      mainClass = finalMainClassOpt(),
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
      importMap = scalaJSImportMap()
    )
  }

  override def runLocal(args: Task[Args] = T.task(Args())): Command[Unit] = T.command { run(args) }

  override def run(args: Task[Args] = T.task(Args())): Command[Unit] = T.command {
    if (args().value.nonEmpty) {
      T.log.error("Passing command line arguments to run is not supported by Scala.js.")
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
  ): Command[Unit] = T.command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  override def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] =
    T.command[Unit] {
      mill.api.Result.Failure("runMain is not supported in Scala.js")
    }

  private[scalajslib] def linkJs(
      worker: ScalaJSWorker,
      toolsClasspath: Agg[PathRef],
      runClasspath: Agg[PathRef],
      mainClass: Either[String, String],
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
      importMap: Seq[ESModuleImportMapping]
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
      importMap = importMap
    )
  }

  override def mandatoryScalacOptions = T {
    // Don't add flag twice, e.g. if a test suite inherits it both directly
    // ScalaJSModule as well as from the enclosing non-test ScalaJSModule
    val scalajsFlag =
      if (
        ZincWorkerUtil.isScala3(scalaVersion()) &&
        !super.mandatoryScalacOptions().contains("-scalajs")
      ) Seq("-scalajs")
      else Seq.empty

    super.mandatoryScalacOptions() ++ scalajsFlag
  }

  override def scalacPluginIvyDeps = T {
    super.scalacPluginIvyDeps() ++ {
      if (ZincWorkerUtil.isScala3(scalaVersion())) {
        Seq.empty
      } else {
        Seq(ivy"org.scala-js:::scalajs-compiler:${scalaJSVersion()}")
      }
    }
  }

  /** Adds the Scala.js Library as mandatory dependency. */
  override def mandatoryIvyDeps = T {
    val prev = super.mandatoryIvyDeps()
    val scalaVer = scalaVersion()
    val scalaJSVer = scalaJSVersion()

    val scalaJSLibrary =
      ivy"org.scala-js::scalajs-library:$scalaJSVer".withDottyCompat(scalaVer)

    /* For Scala 2.x and Scala.js >= 1.15.0, explicitly add scalajs-scalalib,
     * in order to support forward binary incompatible changesin the standard library.
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
  def artifactScalaJSVersion: T[String] = T {
    if (crossFullScalaJSVersion()) scalaJSVersion()
    else scalaJSBinaryVersion()
  }

  override def platformSuffix: Target[String] = s"_sjs${artifactScalaJSVersion()}"

  def jsEnvConfig: Target[JsEnvConfig] = T { JsEnvConfig.NodeJs() }

  def moduleKind: Target[ModuleKind] = T { ModuleKind.NoModule }

  def esFeatures: T[ESFeatures] = T {
    if (scalaJSVersion().startsWith("0."))
      ESFeatures.Defaults.withESVersion(ESVersion.ES5_1)
    else
      ESFeatures.Defaults
  }

  def moduleSplitStyle: Target[ModuleSplitStyle] = T { ModuleSplitStyle.FewestModules }

  def scalaJSOptimizer: Target[Boolean] = T { true }

  def scalaJSImportMap: Target[Seq[ESModuleImportMapping]] = T {
    Seq.empty[ESModuleImportMapping]
  }

  /** Whether to emit a source map. */
  def scalaJSSourceMap: Target[Boolean] = T { true }

  /** Name patterns for output. */
  def scalaJSOutputPatterns: Target[OutputPatterns] = T { OutputPatterns.Defaults }

  /**
   * Apply Scala.js-specific minification of the produced .js files.
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
  def scalaJSMinify: Target[Boolean] = T { true }

  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(scalaJSToolsClasspath)
      else Seq()
    T.command {
      super.prepareOffline(all)()
      T.sequence(tasks)()
      ()
    }
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
    Some((
      ScalaBuildTarget.dataKind,
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
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
  def scalaJSTestDeps = T {
    defaultResolver().resolveDeps(
      Loose.Agg(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-bridge:${scalaJSVersion()}"
      )
        .map(_.withDottyCompat(scalaVersion()))
    )
  }

  def fastLinkJSTest: Target[Report] = T.persistent {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      mainClass = Left("No main class specified or found"),
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
      importMap = scalaJSImportMap()
    )
  }

  override def testLocal(args: String*): Command[(String, Seq[TestResult])] =
    T.command { test(args: _*) }

  override protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[TestResult])] = T.task {

    val (close, framework) = ScalaJSWorkerExternalModule.scalaJSWorker().getFramework(
      scalaJSToolsClasspath(),
      jsEnvConfig(),
      testFramework(),
      fastLinkJSTest()
    )

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args(),
      T.testReporter,
      TestRunnerUtils.globFilter(globSelectors())
    )
    val res = TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())
    // Hack to try and let the Node.js subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }

}
