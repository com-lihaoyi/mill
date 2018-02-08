package mill.scalajslib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
import mill.define.Discover
import mill.{Agg, T}

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

class ScalaJSWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaJSBridge)]

  private def bridge(toolsClasspath: Agg[Path]) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = new URLClassLoader(
          toolsClasspath.map(_.toIO.toURI.toURL).toArray,
          getClass.getClassLoader
        )
        val bridge = cl
          .loadClass("mill.scalajslib.bridge.ScalaJSBridge")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[ScalaJSBridge]
        scalaInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
  }

  def link(toolsClasspath: Agg[Path],
           sources: Agg[Path],
           libraries: Agg[Path],
           dest: File,
           main: Option[String],
           fullOpt: Boolean): Unit = {
    bridge(toolsClasspath).link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      fullOpt
    )
  }

  def run(toolsClasspath: Agg[Path], linkedFile: File): Unit = {
    bridge(toolsClasspath).run(linkedFile)
  }

  def getFramework(toolsClasspath: Agg[Path],
                   frameworkName: String,
                   linkedFile: File): sbt.testing.Framework = {
    bridge(toolsClasspath).getFramework(frameworkName, linkedFile)
  }

}

trait ScalaJSBridge {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean): Unit

  def run(linkedFile: File): Unit

  def getFramework(frameworkName: String,
                   linkedFile: File): sbt.testing.Framework

}

object ScalaJSBridge extends mill.define.ExternalModule {

  def scalaJSBridge = T.worker { new ScalaJSWorker() }
  def millDiscover = Discover[this.type]
}
