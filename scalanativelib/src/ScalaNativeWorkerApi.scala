package mill.scalanativelib

import java.io.File
import java.net.URLClassLoader

import mill.define.{Discover, Worker}
import mill.{Agg, T}
import mill.scalanativelib.api._

class ScalaNativeWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaNativeWorkerApi)]

  def impl(toolsClasspath: Agg[os.Path])(implicit ctx: mill.api.Ctx.Home): ScalaNativeWorkerApi = {
    val classloaderSig = toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          toolsClasspath.map(_.toIO.toURI.toURL).toSeq,
          getClass.getClassLoader
        )
        try {
          val bridge = cl
            .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[ScalaNativeWorkerApi]
          scalaInstanceCache = Some((classloaderSig, bridge))
          bridge
        } catch {
          case e: Exception =>
            e.printStackTrace()
            throw e
        }
    }
  }
}

object ScalaNativeWorkerApi extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
