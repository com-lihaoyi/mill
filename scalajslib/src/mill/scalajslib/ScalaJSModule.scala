package mill
package scalajslib

import ammonite.ops.{Path, exists, ls, mkdir, rm}
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.{PathRef, Result}
import mill.eval.Result.Success
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{CompilationResult, Dep, DepSyntax, TestModule}
import mill.util.{Ctx, Loose}

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  trait Tests extends TestScalaJSModule {
    override def scalaWorker = outer.scalaWorker
    override def scalaVersion = outer.scalaVersion()
    override def scalaJSVersion = outer.scalaJSVersion()
    override def moduleDeps = Seq(outer)
  }

  private val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  private val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r

  def scalaJSBinaryVersion = T{
    scalaJSVersion() match {
      case ReleaseVersion(major, minor, _) => s"$major.$minor"
      case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
      case _ => scalaJSVersion()
    }
  }

  def scalaJSBridgeVersion = T{ scalaJSVersion().split('.').dropRight(1).mkString(".") }

  def sjsBridgeClasspath = T {
    val jsBridgeKey = "MILL_SCALAJS_BRIDGE_" + scalaJSBridgeVersion().replace('.', '_')
    val jsBridgePath = sys.props(jsBridgeKey)
    if (jsBridgePath != null) Success(
      Agg(PathRef(Path(jsBridgePath), quick = true))
    ) else resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      "2.12.4",
      Seq(
        ivy"com.lihaoyi::mill-scalajslib-jsbridges-${scalaJSBridgeVersion()}:${sys.props("MILL_VERSION")}"
      )
    ).map(_.filter(_.path.toString.contains("mill-scalajslib-jsbridges")))
  }

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T{
    val commonDeps = Seq(
      ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}",
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJSVersion()}"
    )
    val envDep = scalaJSBinaryVersion() match {
      case v if v.startsWith("0.6") => ivy"org.scala-js::scalajs-js-envs:${scalaJSVersion()}"
      case v if v.startsWith("1.0") => ivy"org.scala-js::scalajs-env-nodejs:${scalaJSVersion()}"
    }
    resolveDependencies(
      repositories,
      "2.12.4",
      commonDeps :+ envDep
    )
  }

  def toolsClasspath = T { sjsBridgeClasspath() ++ scalaJSLinkerClasspath() }

  def fastOpt = T {
    link(
      ScalaJSBridge.scalaJSBridge(),
      toolsClasspath(),
      runClasspath(),
      finalMainClassOpt().toOption,
      FastOpt,
      moduleKind()
    )
  }

  def fullOpt = T {
    link(
      ScalaJSBridge.scalaJSBridge(),
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
        ScalaJSBridge.scalaJSBridge().run(
          toolsClasspath().map(_.path),
          nodeJSConfig(),
          fastOpt().path.toIO
        )
        Result.Success(())
    }

  }

  override def runMainLocal(mainClass: String, args: String*) = T.command[Unit] {
    mill.eval.Result.Failure("runMain is not supported in Scala.js")
  }

  override def runMain(mainClass: String, args: String*) = T.command[Unit] {
    mill.eval.Result.Failure("runMain is not supported in Scala.js")
  }

  def link(worker: ScalaJSWorker,
           toolsClasspath: Agg[PathRef],
           runClasspath: Agg[PathRef],
           mainClass: Option[String],
           mode: OptimizeMode,
           moduleKind: ModuleKind)(implicit ctx: Ctx): PathRef = {
    val outputPath = ctx.dest / "out.js"

    mkdir(ctx.dest)
    rm(outputPath)

    val classpath = runClasspath.map(_.path)
    val sjsirFiles = classpath
      .filter(path => exists(path) && path.isDir)
      .flatMap(ls.rec)
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
    )
    PathRef(outputPath)
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

  override def artifactSuffix: T[String] = T {
    s"_sjs${artifactScalaJSVersion()}_${artifactScalaVersion()}"
  }

  override def platformSuffix = s"_sjs${artifactScalaJSVersion()}"

  def nodeJSConfig = T { NodeJSConfig() }

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
      ScalaJSBridge.scalaJSBridge(),
      toolsClasspath(),
      scalaJSTestDeps() ++ runClasspath(),
      None,
      FastOpt,
      moduleKind()
    )
  }

  override def testLocal(args: String*) = T.command { test(args:_*) }

  override def test(args: String*) = T.command {
    val (close, framework) = mill.scalajslib.ScalaJSBridge.scalaJSBridge().getFramework(
        toolsClasspath().map(_.path),
        nodeJSConfig(),
        testFrameworks().head,
        fastOptTest().path.toIO
      )

    val (doneMsg, results) = scalaWorker
      .worker()
      .runTests(
        _ => Seq(framework),
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args
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
