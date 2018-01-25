package mill
package scalajslib

import java.io.File

import mill.scalalib.DepSyntax
import ammonite.ops.{Path, ls, mkdir, rm}
import coursier.maven.MavenRepository
import mill.eval.PathRef
import mill.eval.Result.Success
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, PublishModule, ScalaModule, TestModule}
import mill.util.Loose

import scala.collection.breakOut

trait ScalaJSModule extends scalalib.ScalaModule { outer =>

  def scalaJSVersion: T[String]

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

  def sjsLinkerBridgeClasspath = T {
    val jsBridgeKey = "MILL_SCALAJS_BRIDGE_" + scalaJSBridgeVersion().replace('.', '_')
    val jsBridgePath = sys.props(jsBridgeKey)
    if (jsBridgePath != null) Success(
      PathRef(Path(jsBridgePath), quick = true)
    ) else resolveDependencies(
      Seq(MavenRepository("https://repo1.maven.org/maven2")),
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
    resolveDependencies(
      repositories,
      "2.12.4",
      "2.12",
      Seq(ivy"org.scala-js::scalajs-tools:${scalaJSVersion()}")
    )
  }

  def genericOpt(mode: OptimizeMode) = T.task{
    val outputPath = T.ctx().dest / "out.js"

    mkdir(T.ctx().dest)
    rm(outputPath)
    val inputFiles = Agg.from(ls(compile().classes.path).filter(_.ext == "sjsir"))
    val inputLibraries = compileDepClasspath().map(_.path).filter(_.ext == "jar")
    mill.scalajslib.ScalaJSLinkerBridge.scalaJSLinkerBridge().link(
      (Agg(sjsLinkerBridgeClasspath()) ++ scalaJSLinkerClasspath()).map(_.path),
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

  override def ivyDeps = T{ Loose.Agg(Dep("org.scala-js", "scalajs-library", scalaJSVersion())) }

  // publish artifact with name "mill_sjs0.6.4_2.12" instead of "mill_sjs0.6_2.12"
  def crossFullScalaJSVersion: T[Boolean] = false
  def artifactScalaJSVersion: T[String] = T {
    if (crossFullScalaJSVersion()) scalaJSVersion()
    else scalaJSBinaryVersion()
  }

  override def artifactId: T[String] = T { s"${artifactName()}_sjs${artifactScalaJSVersion()}_${artifactScalaVersion()}" }

}

trait TestScalaJSModule extends ScalaJSModule with TestModule