package mill
package scalajslib

import mainargs.Flag
import mill.api.{Loose, PathRef, Result, internal}
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, DepSyntax, Lib, TestModule}
import mill.testrunner.{TestResult, TestRunner, TestRunnerUtils}
import mill.define.{Command, Target, Task}
import mill.scalajslib.api._
import mill.scalajslib.internal.ScalaJSUtils.getReportMainFilePathRef
import mill.scalajslib.worker.{ScalaJSWorker, ScalaJSWorkerExternalModule}
import mill.scalalib.bsp.{ScalaBuildTarget, ScalaPlatform}

import scala.jdk.CollectionConverters._

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  @deprecated("use ScalaJSTests", "0.11.0")
  type ScalaJSModuleTests = ScalaJSTests
  trait ScalaJSTests extends ScalaTests with TestScalaJSModule {
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleKind = outer.moduleKind()
    override def moduleSplitStyle = outer.moduleSplitStyle()
    override def esFeatures = outer.esFeatures()
    override def jsEnvConfig = outer.jsEnvConfig()
    override def scalaJSOptimizer = outer.scalaJSOptimizer()
  }

  def scalaJSBinaryVersion = T { ZincWorkerUtil.scalaJSBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = T { ZincWorkerUtil.scalaJSWorkerVersion(scalaJSVersion()) }

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
      (commonDeps ++ envDeps).map(Lib.depToBoundDep(_, mill.main.BuildInfo.scalaVersion, "")),
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
      mainClass = finalMainClassOpt().toOption,
      forceOutJs = forceOutJs,
      testBridgeInit = false,
      isFullLinkJS = isFullLinkJS,
      optimizer = scalaJSOptimizer(),
      sourceMap = scalaJSSourceMap(),
      moduleKind = moduleKind(),
      esFeatures = esFeatures(),
      moduleSplitStyle = moduleSplitStyle(),
      outputPatterns = scalaJSOutputPatterns()
    )
  }

  override def runLocal(args: Task[Args] = T.task(Args())): Command[Unit] = T.command { run(args) }

  override def run(args: Task[Args] = T.task(Args())): Command[Unit] = T.command {
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

  override def runMainLocal(mainClass: String, args: String*): Command[Unit] = T.command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  override def runMain(mainClass: String, args: String*): Command[Unit] = T.command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  private[scalajslib] def linkJs(
      worker: ScalaJSWorker,
      toolsClasspath: Agg[PathRef],
      runClasspath: Agg[PathRef],
      mainClass: Option[String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns
  )(implicit ctx: mill.api.Ctx): Result[Report] = {
    val outputPath = ctx.dest

    os.makeDir.all(ctx.dest)

    val classpath = runClasspath.map(_.path)
    val sjsirFiles = classpath
      .filter(path => os.exists(path) && os.isDir(path))
      .flatMap(os.walk(_))
      .filter(_.ext == "sjsir")
    val libraries = classpath.filter(_.ext == "jar")
    worker.link(
      toolsClasspath = toolsClasspath,
      sources = sjsirFiles,
      libraries = libraries,
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
      outputPatterns = outputPatterns
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
    super.mandatoryIvyDeps() ++ Seq(
      ivy"org.scala-js::scalajs-library:${scalaJSVersion()}".withDottyCompat(scalaVersion())
    )
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
    ESFeatures.Defaults.withESVersion(ESVersion.ES5_1)
  }

  def moduleSplitStyle: Target[ModuleSplitStyle] = T { ModuleSplitStyle.FewestModules }

  def scalaJSOptimizer: Target[Boolean] = T { true }

  /** Whether to emit a source map. */
  def scalaJSSourceMap: Target[Boolean] = T { true }

  /** Name patterns for output. */
  def scalaJSOutputPatterns: Target[OutputPatterns] = T { OutputPatterns.Defaults }

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
        jars = scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq,
        jvmBuildTarget = None
      )
    ))
  }

}

trait TestScalaJSModule extends ScalaJSModule with TestModule {

  def scalaJSTestDeps = T {
    resolveDeps(T.task {
      val bind = bindDependency()
      Loose.Agg(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-bridge:${scalaJSVersion()}"
      )
        .map(_.withDottyCompat(scalaVersion()))
        .map(bind)
    })
  }

  def fastLinkJSTest: Target[Report] = T.persistent {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      mainClass = None,
      forceOutJs = false,
      testBridgeInit = true,
      isFullLinkJS = false,
      optimizer = scalaJSOptimizer(),
      sourceMap = scalaJSSourceMap(),
      moduleKind = moduleKind(),
      esFeatures = esFeatures(),
      moduleSplitStyle = moduleSplitStyle(),
      outputPatterns = scalaJSOutputPatterns()
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
    val res = TestModule.handleResults(doneMsg, results, Some(T.ctx()))
    // Hack to try and let the Node.js subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }

}
