package mill.scalajslib

import java.io.File
import java.net.URLClassLoader

import mill.define.Discover
import mill.eval.Result
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
  private var scalaInstanceCache = Option.empty[(Long, ScalaJSWorkerApi)]

  private def bridge(toolsClasspath: Agg[os.Path])
                    (implicit ctx: Ctx.Home) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.util.ClassLoader.create(
          toolsClasspath.map(_.toIO.toURI.toURL).toVector,
          getClass.getClassLoader
        )
        val bridge = cl
          .loadClass("mill.scalajslib.worker.ScalaJSWorkerImpl")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[ScalaJSWorkerApi]
        scalaInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
  }

  def link(toolsClasspath: Agg[os.Path],
           sources: Agg[os.Path],
           libraries: Agg[os.Path],
           dest: File,
           main: Option[String],
           fullOpt: Boolean,
           moduleKind: ModuleKind)
          (implicit ctx: Ctx.Home): Result[os.Path] = {
    bridge(toolsClasspath).link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      fullOpt,
      moduleKind
    ).map(os.Path(_))
  }

  def run(toolsClasspath: Agg[os.Path], config: NodeJSConfig, linkedFile: File)
         (implicit ctx: Ctx.Home): Unit = {
    bridge(toolsClasspath).run(config, linkedFile)
  }

  def getFramework(toolsClasspath: Agg[os.Path],
                   config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File)
                  (implicit ctx: Ctx.Home): (() => Unit, sbt.testing.Framework) = {
    bridge(toolsClasspath).getFramework(config, frameworkName, linkedFile)
  }

}

trait ScalaJSWorkerApi {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean,
           moduleKind: ModuleKind): Result[File]

  def run(config: NodeJSConfig, linkedFile: File): Unit

  def getFramework(config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File): (() => Unit, sbt.testing.Framework)

}

object ScalaJSWorkerApi extends mill.define.ExternalModule {

  def scalaJSWorker = T.worker { new ScalaJSWorker() }
  lazy val millDiscover = Discover[this.type]
}
