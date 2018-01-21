package mill
package scalajslib

import ammonite.ops.{Path, ls, mkdir, rm}
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.PathRef
import mill.eval.Result.Success
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, DepSyntax, TestModule}
import mill.util.Loose

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
      "2.12",
      Seq(Dep(
        "com.lihaoyi",
        s"mill-jsbridge_${scalaJSBridgeVersion().replace('.', '_')}",
        "0.1-SNAPSHOT"
      ))
    ).map(_.find(_.path.toString.contains("mill-jsbridge")).get)
  }

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T{
    val commonDeps = Seq(
      ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}",
      ivy"org.scala-js::scalajs-sbt-test-adapter:${scalaJSVersion()}",
      ivy"org.scala-js::scalajs-test-interface:${scalaJSVersion()}"
    )
    val envDep = scalaJSBinaryVersion() match {
      case v if v.startsWith("0.6") => ivy"org.scala-js::scalajs-js-envs:${scalaJSVersion()}"
      case v if v.startsWith("1.0") => ivy"org.scala-js::scalajs-env-nodejs:${scalaJSVersion()}"
    }
    resolveDependencies(
      repositories,
      "2.12.4",
      "2.12",
      commonDeps :+ envDep
    )
  }

  def genericOpt(mode: OptimizeMode) = T.task{
    val outputPath = T.ctx().dest / "out.js"

    mkdir(T.ctx().dest)
    rm(outputPath)
    val inputFiles = Agg.from(ls(compile().classes.path).filter(_.ext == "sjsir"))
    val inputLibraries = compileDepClasspath().map(_.path).filter(_.ext == "jar")
    mill.scalajslib.ScalaJSBridge.scalaJSBridge().link(
      (Agg(sjsBridgeClasspath()) ++ scalaJSLinkerClasspath()).map(_.path),
      inputFiles,
      inputLibraries,
      outputPath.toIO,
      mainClass(),
      mode == FullOpt
    )
    PathRef(outputPath)
  }

  def fastOpt = T{ genericOpt(FastOpt)() }

  def fullOpt = T{ genericOpt(FullOpt)() }

  override def scalacPluginIvyDeps = T{ Loose.Agg(Dep.Point("org.scala-js", "scalajs-compiler", scalaJSVersion())) }

  override def ivyDeps = T{ Loose.Agg(ivy"org.scala-js::scalajs-library:${scalaJSVersion()}") }

  // publish artifact with name "mill_sjs0.6.4_2.12" instead of "mill_sjs0.6_2.12"
  def crossFullScalaJSVersion: T[Boolean] = false
  def artifactScalaJSVersion: T[String] = T {
    if (crossFullScalaJSVersion()) scalaJSVersion()
    else scalaJSBinaryVersion()
  }

  override def artifactId: T[String] = T { s"${artifactName()}_sjs${artifactScalaJSVersion()}_${artifactScalaVersion()}" }

}

trait TestScalaJSModule extends ScalaJSModule with TestModule {
  def scalaJSTestDeps = T {
    Loose.Agg(
      ivy"org.scala-js::scalajs-library:${scalaJSVersion()}",
      ivy"org.scala-js::scalajs-test-interface:${scalaJSVersion()}"
    )
  }

  def fastOptTest = T {
    val outputPath = T.ctx().dest / "out.js"

    mkdir(T.ctx().dest)
    rm(outputPath)

    val inputFiles = Agg.from(for {
      compiled <- compile() +: upstreamCompileOutput()
      classes <- ls(compiled.classes.path).filter(_.ext == "sjsir")
    } yield classes)

    val inputLibraries = (resolveDeps(scalaJSTestDeps)() ++ compileDepClasspath()).map(_.path).filter(_.ext == "jar")

    mill.scalajslib.ScalaJSBridge.scalaJSBridge().link(
      toolsClasspath = (Agg(sjsBridgeClasspath()) ++ scalaJSLinkerClasspath()).map(_.path),
      sources = inputFiles,
      libraries = inputLibraries,
      dest = outputPath.toIO,
      main = None,
      fullOpt = false
    )
    PathRef(outputPath)
  }

  override def test(args: String*) = testLocal(args:_*)

  override def testLocal(args: String*) = T.command {
    val framework = mill.scalajslib.ScalaJSBridge.scalaJSBridge().getFramework(
        (Agg(sjsBridgeClasspath()) ++ scalaJSLinkerClasspath()).map(_.path),
        testFramework(),
        fastOptTest().path.toIO
      )

    val (doneMsg, results) = mill.scalalib.ScalaWorkerApi
      .scalaWorker()
      .apply(
        _ => framework,
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args
      )
    TestModule.handleResults(doneMsg, results)
  }

}
