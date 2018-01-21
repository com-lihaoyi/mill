package mill.scalajslib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
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
        val outerClassLoader = getClass.getClassLoader
        val cl = new URLClassLoader(
          toolsClasspath.map(_.toIO.toURI.toURL).toArray) {
          override def findClass(name: String) = {
            if (name.startsWith("mill.scalajslib.ScalaJSBridge")) {
              outerClassLoader.loadClass(name)
            } else {
              super.findClass(name)
            }
          }
        }
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

  def getFramework(frameworkName: String,
                   linkedFile: File): sbt.testing.Framework

}

object ScalaJSBridge extends mill.define.BaseModule(ammonite.ops.pwd) {

  def scalaJSBridge = T.worker { new ScalaJSWorker() }
}
