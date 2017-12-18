package mill
package scalajsplugin

import java.io.File

import ammonite.ops.Path
import mill.eval.Result.Success
import mill.scalajsplugin.Lib._
import mill.scalaplugin.Lib.resolveDependencies
import mill.scalaplugin.{Dep, ScalaModule, TestScalaModule}

trait ScalaJSModule extends ScalaModule { outer =>

  def scalaJSVersion: T[String]

  def scalaJSBinaryVersion = T{ scalaJSVersion().split('.').dropRight(1).mkString(".") }

  def scalaJSLinkerClasspath: T[Seq[PathRef]] = T{
    val jsBridgeKey = "MILL_SCALAJS_BRIDGE_" + scalaJSBinaryVersion().replace('.', '_')
    val jsBridgePath = sys.props(jsBridgeKey)
    if (jsBridgePath != null) Success(jsBridgePath.split(File.pathSeparator).map(f => PathRef(Path(f), quick = true)).toVector)
    else {
      val dep = scalaJSLinkerIvyDep(scalaJSBinaryVersion())
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

  override def scalacPluginIvyDeps = T{ Seq(Dep.Point("org.scala-js", "scalajs-compiler", scalaJSVersion())) }

  override def ivyDeps = T{ Seq(Dep("org.scala-js", "scalajs-library", scalaJSVersion())) }

}

trait TestScalaJSModule extends ScalaJSModule with TestScalaModule