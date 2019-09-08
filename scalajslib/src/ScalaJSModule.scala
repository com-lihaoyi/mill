package mill
package scalajslib

import coursier.maven.MavenRepository
import mill.eval.{PathRef, Result}
import mill.api.Result.Success
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{DepSyntax, Lib, TestModule, TestRunner}
import mill.util.Ctx
import mill.api.Loose
import mill.scalajslib.api._
trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  trait Tests extends TestScalaJSModule {
    override def zincWorker = outer.zincWorker
    override def scalaOrganization = outer.scalaOrganization()
    override def scalaVersion = outer.scalaVersion()
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleDeps = Seq(outer)
  }

  def scalaJSBinaryVersion = T { mill.scalalib.api.Util.scalaBinaryVersion(scalaJSVersion()) }

  def scalaJSWorkerVersion = T{ scalaJSVersion().split('.').dropRight(1).mkString(".") }

  def scalaJSWorkerClasspath = T {
    val workerKey = "MILL_SCALAJS_WORKER_" + scalaJSWorkerVersion().replace('.', '_')
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-scalajslib-worker-${scalaJSWorkerVersion()}",
      repositories,
      resolveFilter = _.toString.contains("mill-scalajslib-worker")
    )
  }

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T{
    val commonDeps = Seq(
      ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}",
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJSVersion()}",
      ivy"org.eclipse.jetty:jetty-websocket:8.1.16.v20140903",
      ivy"org.eclipse.jetty:jetty-server:8.1.16.v20140903",
      ivy"org.eclipse.jetty.orbit:javax.servlet:3.0.0.v201112011016"
    )
    val envDep = scalaJSBinaryVersion() match {
      case v if v.startsWith("0.6") => Seq(ivy"org.scala-js::scalajs-js-envs:${scalaJSVersion()}")
      case v if v.startsWith("1.0") =>
        Seq(
          ivy"org.scala-js::scalajs-env-nodejs:${scalaJSVersion()}",
          ivy"org.scala-js::scalajs-env-jsdom-nodejs:${scalaJSVersion()}",
          ivy"org.scala-js::scalajs-env-phantomjs:${scalaJSVersion()}"
        )
    }
    resolveDependencies(
      repositories,
      Lib.depToDependency(_, "2.12.4", ""),
      commonDeps ++ envDep,
      ctx = Some(implicitly[mill.util.Ctx.Log])
    )
  }

  def toolsClasspath = T { scalaJSWorkerClasspath() ++ scalaJSLinkerClasspath() }

  def fastOpt = T {
    link(
      ScalaJSWorkerApi.scalaJSWorker(),
      toolsClasspath(),
      runClasspath(),
      finalMainClassOpt().toOption,
      FastOpt,
      moduleKind()
    )
  }

  def fullOpt = T {
    link(
      ScalaJSWorkerApi.scalaJSWorker(),
      toolsClasspath(),
      runClasspath(),
      finalMainClassOpt().toOption,
      FullOpt,
      moduleKind()
    )
  }

  override def runLocal(args: String*) = T.command { run(args:_*) }

  override def run(args: String*) = T.command {
    finalMainClassOpt() match{
      case Left(err) => Result.Failure(err)
      case Right(_) =>
        ScalaJSWorkerApi.scalaJSWorker().run(
          toolsClasspath().map(_.path),
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

  def link(worker: ScalaJSWorker,
           toolsClasspath: Agg[PathRef],
           runClasspath: Agg[PathRef],
           mainClass: Option[String],
           mode: OptimizeMode,
           moduleKind: ModuleKind)(implicit ctx: Ctx): Result[PathRef] = {
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
      mode == FullOpt,
      moduleKind
    ).map(PathRef(_))
  }

  override def scalacPluginIvyDeps = T{
    super.scalacPluginIvyDeps() ++
    Seq(ivy"org.scala-js:::scalajs-compiler:${scalaJSVersion()}")
  }
  override def scalaLibraryIvyDeps = T{
    Seq(ivy"org.scala-js::scalajs-library:${scalaJSVersion()}")
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
}

trait TestScalaJSModule extends ScalaJSModule with TestModule {
  def scalaJSTestDeps = T {
    resolveDeps(T.task {
      Loose.Agg(
        ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
        ivy"org.scala-js::scalajs-test-interface:${scalaJSVersion()}"
      )
    })
  }

  def fastOptTest = T {
    link(
      ScalaJSWorkerApi.scalaJSWorker(),
      toolsClasspath(),
      scalaJSTestDeps() ++ runClasspath(),
      None,
      FastOpt,
      moduleKind()
    )
  }

  override def testLocal(args: String*) = T.command { test(args:_*) }

  override def test(args: String*) = T.command {
    val (close, framework) = mill.scalajslib.ScalaJSWorkerApi.scalaJSWorker().getFramework(
        toolsClasspath().map(_.path),
        jsEnvConfig(),
        testFrameworks().head,
        fastOptTest().path.toIO
      )

    val (doneMsg, results) = TestRunner.runTests(
        _ => Seq(framework),
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args,
        T.ctx.testReporter
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
