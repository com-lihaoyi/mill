package mill
package scalajslib

import ammonite.ops.{Path, ls, mkdir, rm}
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.PathRef
import mill.eval.Result.Success
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{CompilationResult, Dep, DepSyntax, TestModule}
import mill.util.{Ctx, Loose}

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

  trait Tests extends TestScalaJSModule {
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
      PathRef(Path(jsBridgePath), quick = true)
    ) else resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      "2.12.4",
      Seq(
        ivy"com.lihaoyi::mill-scalajslib-jsbridges-${scalaJSBridgeVersion()}:${sys.props("MILL_VERSION")}"
      )
    ).map(_.find(_.path.toString.contains("mill-jsbridge")).get)
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

  def toolsClasspath = T { Agg(sjsBridgeClasspath()) ++ scalaJSLinkerClasspath() }

  def fastOpt = T {
    link(
      ScalaJSBridge.scalaJSBridge(),
      toolsClasspath(),
      Seq(compile()),
      runClasspath(),
      mainClass(),
      FastOpt
    )
  }

  def fullOpt = T {
    link(
      ScalaJSBridge.scalaJSBridge(),
      toolsClasspath(),
      Seq(compile()),
      runClasspath(),
      mainClass(),
      FullOpt
    )
  }

  override  def runLocal(args: String*) = T.command { run(args:_*) }

  override def run(args: String*) = T.command {
    if(mainClass().isEmpty) {
      throw new RuntimeException("No mainClass provided!")
    }

    ScalaJSBridge.scalaJSBridge().run(
      toolsClasspath().map(_.path),
      fastOpt().path.toIO
    )
  }

  override def runMainLocal(mainClass: String, args: String*) = T.command { runMain(mainClass, args:_*) }

  override def runMain(mainClass: String, args: String*) = T.command {
    val linkedFile = link(
      ScalaJSBridge.scalaJSBridge(),
      toolsClasspath(),
      Seq(compile()),
      runClasspath(),
      Some(mainClass),
      FastOpt
    )

    ScalaJSBridge.scalaJSBridge().run(
      toolsClasspath().map(_.path),
      linkedFile.path.toIO
    )
  }

  def link(worker: ScalaJSWorker,
           toolsClasspath: Agg[PathRef],
           input: Seq[CompilationResult],
           libraries: Agg[PathRef],
           mainClass: Option[String],
           mode: OptimizeMode)(implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.js"

    mkdir(ctx.dest)
    rm(outputPath)

    val inputFiles = Agg.from(for {
      compiled <- input
      file <- ls(compiled.classes.path)
      if file.ext == "sjsir"
    } yield file)
    val inputLibraries = libraries.map(_.path).filter(_.ext == "jar")
    worker.link(
      toolsClasspath.map(_.path),
      inputFiles,
      inputLibraries,
      outputPath.toIO,
      mainClass,
      mode == FullOpt
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
      compile() +: upstreamCompileOutput(),
      scalaJSTestDeps() ++ runClasspath(),
      None,
      FastOpt
    )
  }

  override def testLocal(args: String*) = T.command { test(args:_*) }

  override def test(args: String*) = T.command {
    val framework = mill.scalajslib.ScalaJSBridge.scalaJSBridge().getFramework(
        toolsClasspath().map(_.path),
        testFramework(),
        fastOptTest().path.toIO
      )

    val (doneMsg, results) = mill.scalalib.ScalaWorkerApi
      .scalaWorker()
      .runTests(
        _ => framework,
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args
      )
    TestModule.handleResults(doneMsg, results)
  }

}
