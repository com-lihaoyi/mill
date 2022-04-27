package mill
package scalajslib

import ch.epfl.scala.bsp4j.{BuildTargetDataKind, ScalaBuildTarget, ScalaPlatform}
import mill.api.{Loose, PathRef, Result, internal}
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{DepSyntax, Lib, TestModule}
import mill.testrunner.TestRunner
import mill.define.{Command, Target, Task}
import mill.scalajslib.{ScalaJSWorker => DeprecatedScalaJSWorker}
import mill.scalajslib.api._
import mill.scalajslib.worker._

import scala.jdk.CollectionConverters._

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  trait Tests extends TestScalaJSModule {
    override def zincWorker = outer.zincWorker
    override def scalaOrganization = outer.scalaOrganization()
    override def scalaVersion = outer.scalaVersion()
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleDeps = Seq(outer)
  }

  def scalaJSBinaryVersion = T { ZincWorkerUtil.scalaJSBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = T { ZincWorkerUtil.scalaJSWorkerVersion(scalaJSVersion()) }

  def scalaJSWorkerClasspath = T {
    val workerKey = "MILL_SCALAJS_WORKER_" + scalaJSWorkerVersion().replace('.', '_')
    mill.modules.Util.millProjectModule(
      key = workerKey,
      artifact = s"mill-scalajslib-worker-${scalaJSWorkerVersion()}",
      repositories = repositoriesTask(),
      resolveFilter = _.toString.contains("mill-scalajslib-worker")
    )
  }

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T {
    val commonDeps = Seq(
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJSVersion()}",
      ivy"${ScalaJSBuildInfo.Deps.jettyWebsocket}",
      ivy"${ScalaJSBuildInfo.Deps.jettyServer}",
      ivy"${ScalaJSBuildInfo.Deps.javaxServlet}"
    )
    val envDeps = scalaJSBinaryVersion() match {
      case "0.6" =>
        Seq(
          ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}",
          ivy"org.scala-js::scalajs-js-envs:${scalaJSVersion()}"
        )
      case "1" =>
        Seq(
          ivy"org.scala-js::scalajs-linker:${scalaJSVersion()}",
          ivy"${ScalaJSBuildInfo.Deps.scalajsEnvNodejs}",
          ivy"${ScalaJSBuildInfo.Deps.scalajsEnvJsdomNodejs}",
          ivy"${ScalaJSBuildInfo.Deps.scalajsEnvPhantomJs}"
        )
    }
    // we need to use the scala-library of the currently running mill
    resolveDependencies(
      repositoriesTask(),
      Lib.depToDependency(_, mill.BuildInfo.scalaVersion, ""),
      commonDeps ++ envDeps,
      ctx = Some(T.log)
    )
  }

  @deprecated("Use scalaJSToolsClasspath instead", "mill after 0.10.0-M1")
  def toolsClasspath = T { scalaJSToolsClasspath() }

  def scalaJSToolsClasspath = T { scalaJSWorkerClasspath() ++ scalaJSLinkerClasspath() }

  def fastLinkJS: Target[Report] = T {
    linkTask(optimize = false, legacy = false)()
  }

  def fullLinkJS: Target[Report] = T {
    linkTask(optimize = true, legacy = false)()
  }

  def fastOpt: Target[PathRef] = T {
    linkTask(optimize = false, legacy = true)().publicModules.head.jsFile
  }

  def fullOpt: Target[PathRef] = T {
    linkTask(optimize = true, legacy = true)().publicModules.head.jsFile
  }

  private def linkTask(optimize: Boolean, legacy: Boolean): Task[Report] = T.task {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = runClasspath(),
      mainClass = finalMainClassOpt().toOption,
      legacy = legacy,
      testBridgeInit = false,
      optimize = optimize,
      moduleKind = moduleKind(),
      esFeatures = esFeatures()
    )
  }

  override def runLocal(args: String*) = T.command { run(args: _*) }

  override def run(args: String*) = T.command {
    finalMainClassOpt() match {
      case Left(err) => Result.Failure(err)
      case Right(_) =>
        ScalaJSWorkerExternalModule.scalaJSWorker().run(
          scalaJSToolsClasspath().map(_.path),
          jsEnvConfig(),
          fastLinkJS()
        )
        Result.Success(())
    }

  }

  override def runMainLocal(mainClass: String, args: String*) = T.command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  override def runMain(mainClass: String, args: String*) = T.command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Scala.js")
  }

  @deprecated("Intended for internal usage. To be removed.", since = "0.10.3")
  def link(
      worker: DeprecatedScalaJSWorker,
      toolsClasspath: Agg[PathRef],
      runClasspath: Agg[PathRef],
      mainClass: Option[String],
      testBridgeInit: Boolean,
      mode: OptimizeMode,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  )(implicit ctx: mill.api.Ctx): Result[PathRef] = linkJs(
    worker = worker.newWorker,
    toolsClasspath = toolsClasspath,
    runClasspath = runClasspath,
    mainClass = mainClass,
    legacy = true,
    testBridgeInit = testBridgeInit,
    optimize = mode == FullOpt,
    moduleKind = moduleKind,
    esFeatures = esFeatures
  ).map(report => report.publicModules.head.jsFile)

  private[scalajslib] protected def linkJs(
      worker: ScalaJSWorker,
      toolsClasspath: Agg[PathRef],
      runClasspath: Agg[PathRef],
      mainClass: Option[String],
      legacy: Boolean,
      testBridgeInit: Boolean,
      optimize: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
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
      toolsClasspath.map(_.path),
      sjsirFiles,
      libraries,
      outputPath.toIO,
      mainClass,
      legacy,
      testBridgeInit,
      optimize,
      moduleKind,
      esFeatures
    )
  }

  override def mandatoryScalacOptions = T {
    super.mandatoryScalacOptions() ++ {
      if (ZincWorkerUtil.isScala3(scalaVersion())) Seq("-scalajs")
      else Seq.empty
    }
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

  override def artifactSuffix: Target[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  override def platformSuffix: Target[String] = s"_sjs${artifactScalaJSVersion()}"

  def jsEnvConfig: Target[JsEnvConfig] = T { JsEnvConfig.NodeJs() }

  def moduleKind: Target[ModuleKind] = T { ModuleKind.NoModule }

  @deprecated("Use esFeatures().esVersion instead", since = "mill after 0.10.0-M5")
  def useECMAScript2015: Target[Boolean] = T {
    !scalaJSVersion().startsWith("0.")
  }

  def esFeatures: T[ESFeatures] = T {
    if (useECMAScript2015.ctx.enclosing != s"${classOf[ScalaJSModule].getName}#useECMAScript2015") {
      T.log.error("Overriding `useECMAScript2015` is deprecated. Override `esFeatures` instead")
    }
    if (useECMAScript2015()) ESFeatures.Defaults
    else ESFeatures.Defaults.withESVersion(ESVersion.ES5_1)
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
    Some((
      BuildTargetDataKind.SCALA,
      new ScalaBuildTarget(
        scalaOrganization(),
        scalaVersion(),
        ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        ScalaPlatform.JS,
        scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq.asJava
      )
    ))
  }

}

trait TestScalaJSModule extends ScalaJSModule with TestModule {
  def scalaJSTestDeps = T {
    resolveDeps(T.task {
      val bridgeOrInterface =
        if (ZincWorkerUtil.scalaJSUsesTestBridge(scalaJSVersion())) "bridge"
        else "interface"
      Loose.Agg(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-bridge:${scalaJSVersion()}"
      ).map(_.withDottyCompat(scalaVersion()))
    })
  }

  @deprecated("To be removed. Use fastLinkJSTest instead", since = "0.10.4")
  def fastOptTest = T {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      mainClass = None,
      legacy = true,
      testBridgeInit = true,
      optimize = false,
      moduleKind = moduleKind(),
      esFeatures = esFeatures()
    ).map { report => report.publicModules.head.jsFile }
  }

  def fastLinkJSTest = T {
    linkJs(
      worker = ScalaJSWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      mainClass = None,
      legacy = false,
      testBridgeInit = true,
      optimize = false,
      moduleKind = moduleKind(),
      esFeatures = esFeatures()
    )
  }

  override def testLocal(args: String*): Command[(String, Seq[TestRunner.Result])] =
    T.command { test(args: _*) }

  override protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[TestRunner.Result])] = T.task {

    val (close, framework) = ScalaJSWorkerExternalModule.scalaJSWorker().getFramework(
      scalaJSToolsClasspath().map(_.path),
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
      TestRunner.globFilter(globSelectors())
    )
    val res = TestModule.handleResults(doneMsg, results, Some(T.ctx))
    // Hack to try and let the Node.js subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }

}
