package mill
package scalajslib

import java.io.File

import ammonite.ops.Path
import mill.eval.Result.Success
import mill.scalajslib.Lib._
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, Module, PublishModule, TestModule}
import mill.util.Loose

trait ScalaJSModule extends scalalib.Module { outer =>

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

  def scalaJSLinkerClasspath: T[Loose.Agg[PathRef]] = T{
    val jsBridgeKey = "MILL_SCALAJS_BRIDGE_" + scalaJSBridgeVersion().replace('.', '_')
    val jsBridgePath = sys.props(jsBridgeKey)
    if (jsBridgePath != null) {
      Success(
        Loose.Agg.from(
          jsBridgePath.split(File.pathSeparator).map(f => PathRef(Path(f), quick = true))
        )
      )
    }
    else {
      val dep = scalaJSLinkerIvyDep(scalaJSBridgeVersion())
      resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        Seq(dep)
      )
    }
  }

  def fastOpt = T{
    val linker = scalaJSLinkerBridge(scalaJSLinkerClasspath().map(_.path))
    link(mainClass(), Seq(compile().classes.path), compileDepClasspath().map(_.path), linker, FastOpt)
  }

  def fullOpt = T{
    val linker = scalaJSLinkerBridge(scalaJSLinkerClasspath().map(_.path))
    link(mainClass(), Seq(compile().classes.path), compileDepClasspath().map(_.path), linker, FullOpt)
  }

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