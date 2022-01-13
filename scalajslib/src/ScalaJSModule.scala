package mill
package scalajslib

import ch.epfl.scala.bsp4j.{BuildTargetDataKind, ScalaBuildTarget, ScalaPlatform}
import mill.api.{Loose, PathRef, Result, internal}
import mill.scalalib.api.Util.{isScala3, scalaBinaryVersion}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{DepSyntax, Lib, TestModule}
import mill.testrunner.TestRunner
import mill.util.Ctx
import mill.define.Task
import mill.scalajslib.api._

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

  def scalaJSBinaryVersion = T { mill.scalalib.api.Util.scalaJSBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = T { mill.scalalib.api.Util.scalaJSWorkerVersion(scalaJSVersion()) }

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

  def fastOpt = T {
    link(
      worker = ScalaJSWorkerApi.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = runClasspath(),
      mainClass = finalMainClassOpt().toOption,
      testBridgeInit = false,
      mode = FastOpt,
      moduleKind = moduleKind(),
      esFeatures = esFeatures()
    )
  }

  def fullOpt = T {
    link(
      worker = ScalaJSWorkerApi.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = runClasspath(),
      mainClass = finalMainClassOpt().toOption,
      testBridgeInit = false,
      mode = FullOpt,
      moduleKind = moduleKind(),
      esFeatures = esFeatures()
    )
  }

  override def runLocal(args: String*) = T.command { run(args: _*) }

  override def run(args: String*) = T.command {
    finalMainClassOpt() match {
      case Left(err) => Result.Failure(err)
      case Right(_) =>
        ScalaJSWorkerApi.scalaJSWorker().run(
          scalaJSToolsClasspath().map(_.path),
          jsEnvConfig(),
          fastOpt().path.toIO
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

  def link(
      worker: ScalaJSWorker,
      toolsClasspath: Agg[PathRef],
      runClasspath: Agg[PathRef],
      mainClass: Option[String],
      testBridgeInit: Boolean,
      mode: OptimizeMode,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  )(implicit ctx: Ctx): Result[PathRef] = {
    val outputPath = ctx.dest / "out.js"

    os.makeDir.all(ctx.dest)
    os.remove.all(outputPath)

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
      testBridgeInit,
      mode == FullOpt,
      moduleKind,
      esFeatures
    ).map(PathRef(_))
  }

  override def mandatoryScalacOptions = T {
    super.mandatoryScalacOptions() ++ {
      if (isScala3(scalaVersion())) Seq("-scalajs")
      else Seq.empty
    }
  }

  override def scalacPluginIvyDeps = T {
    super.scalacPluginIvyDeps() ++ {
      if (isScala3(scalaVersion())) {
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

  override def artifactSuffix: T[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  override def platformSuffix = s"_sjs${artifactScalaJSVersion()}"

  def jsEnvConfig: T[JsEnvConfig] = T { JsEnvConfig.NodeJs() }

  def moduleKind: T[ModuleKind] = T { ModuleKind.NoModule }

  @deprecated("Use esFeatures().esVersion instead", since = "mill after 0.10.0-M5")
  def useECMAScript2015: T[Boolean] = T {
    esFeatures().esVersion != ESVersion.ES5_1
  }

  def esFeatures: T[ESFeatures] = T {
    if (useECMAScript2015.ctx.enclosing != s"${classOf[ScalaJSModule].getName}#useECMAScript2015") {
      Result.Failure("Overriding `useECMAScript2015` is not supported anymore. Override `esFeatures` instead")
    } else {
      Result.Success {
        if (scalaJSVersion().startsWith("0.")) ESFeatures.Defaults.withESVersion(ESVersion.ES5_1)
        else ESFeatures.Defaults
      }
    }
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
    Some((
      BuildTargetDataKind.SCALA,
      new ScalaBuildTarget(
        scalaOrganization(),
        scalaVersion(),
        scalaBinaryVersion(scalaVersion()),
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
        if (mill.scalalib.api.Util.scalaJSUsesTestBridge(scalaJSVersion())) "bridge"
        else "interface"
      Loose.Agg(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-bridge:${scalaJSVersion()}"
      ).map(_.withDottyCompat(scalaVersion()))
    })
  }

  def fastOptTest = T {
    link(
      ScalaJSWorkerApi.scalaJSWorker(),
      scalaJSToolsClasspath(),
      scalaJSTestDeps() ++ runClasspath(),
      None,
      testBridgeInit = true,
      FastOpt,
      moduleKind(),
      esFeatures()
    )
  }

  override def testLocal(args: String*) = T.command { test(args: _*) }

  override protected def testTask(
      args: Task[Seq[String]],
      globSeletors: Task[Seq[String]]
  ): Task[(String, Seq[TestRunner.Result])] = T.task {

    val (close, framework) = mill.scalajslib.ScalaJSWorkerApi.scalaJSWorker().getFramework(
      scalaJSToolsClasspath().map(_.path),
      jsEnvConfig(),
      testFramework(),
      fastOptTest().path.toIO,
      moduleKind()
    )

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args(),
      T.testReporter,
      TestRunner.globFilter(globSeletors())
    )
    val res = TestModule.handleResults(doneMsg, results)
    // Hack to try and let the Node.js subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }

}
