package mill.scalajslib

import java.io.File

import mill.api.{Ctx, Result}
import mill.define.Discover
import mill.scalajslib.api._
import mill.{Agg, T}
class ScalaJSWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaJSWorkerApi)]

  private def bridge(toolsClasspath: Agg[os.Path])(implicit ctx: Ctx.Home) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
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

  def link(
      toolsClasspath: Agg[os.Path],
      sources: Agg[os.Path],
      libraries: Agg[os.Path],
      dest: File,
      main: Option[String],
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  )(implicit ctx: Ctx.Home): Result[os.Path] = {
    bridge(toolsClasspath).link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      testBridgeInit,
      fullOpt,
      moduleKind,
      esFeatures
    ).map(os.Path(_))
  }

  def run(toolsClasspath: Agg[os.Path], config: JsEnvConfig, linkedFile: File)(implicit
      ctx: Ctx.Home
  ): Unit = {
    bridge(toolsClasspath).run(config, linkedFile)
  }

  def getFramework(
      toolsClasspath: Agg[os.Path],
      config: JsEnvConfig,
      frameworkName: String,
      linkedFile: File,
      moduleKind: ModuleKind
  )(implicit ctx: Ctx.Home): (() => Unit, sbt.testing.Framework) = {
    bridge(toolsClasspath).getFramework(config, frameworkName, linkedFile, moduleKind)
  }

}

object ScalaJSWorkerApi extends mill.define.ExternalModule {

  def scalaJSWorker = T.worker { new ScalaJSWorker() }
  lazy val millDiscover = Discover[this.type]
}
