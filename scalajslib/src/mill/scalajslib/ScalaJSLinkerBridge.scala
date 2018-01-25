package mill.scalajslib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
import mill.{Agg, T}

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

class ScalaJSLinkerWorker {
  var scalaInstanceCache = Option.empty[(Long, ScalaJSLinkerBridge)]
  def link(toolsClasspath: Agg[Path],
           sources: Agg[Path],
           libraries: Agg[Path],
           dest: File,
           main: Option[String],
           fullOpt: Boolean): Unit = {

    val classloaderSig = {
      toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    }

    val bridge = scalaInstanceCache match{
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val outerClassLoader = getClass.getClassLoader
        val cl = new URLClassLoader(toolsClasspath.map(_.toIO.toURI.toURL).toArray){
          override def findClass(name: String) = {
            if (name.startsWith("mill.scalajslib.ScalaJSLinkerBridge")){
              outerClassLoader.loadClass(name)
            }else{
              super.findClass(name)
            }
          }
        }
        val bridge = cl
          .loadClass("mill.scalajslib.bridge.ScalaJSLinkerBridge")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[ScalaJSLinkerBridge]
        scalaInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
    bridge.link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      fullOpt
    )
  }

}

trait ScalaJSLinkerBridge {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean): Unit
}

object ScalaJSLinkerBridge extends mill.define.BaseModule(ammonite.ops.pwd){

  def scalaJSLinkerBridge = T.worker{
    new ScalaJSLinkerWorker()
  }
}