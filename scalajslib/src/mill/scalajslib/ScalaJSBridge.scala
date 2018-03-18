package mill.scalajslib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
import mill.define.Discover
import mill.util.Ctx
import mill.{Agg, T}

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

sealed trait ModuleKind
object ModuleKind{
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
}

class ScalaJSWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaJSBridge)]

  private def bridge(toolsClasspath: Agg[Path])
                    (implicit ctx: Ctx.Home) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.util.ClassLoader.create(
          toolsClasspath.map(_.toIO.toURI.toURL).toVector,
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
           fullOpt: Boolean,
           moduleKind: ModuleKind)
          (implicit ctx: Ctx.Home): Unit = {
    bridge(toolsClasspath).link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      fullOpt,
      moduleKind
    )
  }

  def run(toolsClasspath: Agg[Path], config: NodeJSConfig, linkedFile: File)
         (implicit ctx: Ctx.Home): Unit = {
    bridge(toolsClasspath).run(config, linkedFile)
  }

  def getFramework(toolsClasspath: Agg[Path],
                   config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File)
                  (implicit ctx: Ctx.Home): sbt.testing.Framework = {
    bridge(toolsClasspath).getFramework(config, frameworkName, linkedFile)
  }

}

trait ScalaJSBridge {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean,
           moduleKind: ModuleKind): Unit

  def run(config: NodeJSConfig, linkedFile: File): Unit

  def getFramework(config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File): sbt.testing.Framework

}

object ScalaJSBridge extends mill.define.ExternalModule {

  def scalaJSBridge = T.worker { new ScalaJSWorker() }
  lazy val millDiscover = Discover[this.type]
}
