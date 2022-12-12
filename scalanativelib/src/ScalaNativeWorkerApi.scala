package mill.scalanativelib

import java.io.File
import java.net.URLClassLoader

import mill.define.{Discover, Worker}
import mill.{Agg, PathRef, T}
import mill.scalanativelib.api._

class ScalaNativeWorker extends AutoCloseable {
  private var scalaInstanceCache = Option.empty[(Long, ScalaNativeWorkerApi)]

  def impl(toolsClasspath: Agg[PathRef])(implicit ctx: mill.api.Ctx.Home): ScalaNativeWorkerApi = {
    val classloaderSig = toolsClasspath.hashCode
    val isScala213 = toolsClasspath.exists(_.path.last.endsWith("_2.13.jar"))
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          toolsClasspath.map(_.path.toIO.toURI.toURL).toSeq,
          parent = if (isScala213) getClass.getClassLoader else null,
          sharedPrefixes =
            if (isScala213) Seq.empty else Seq("mill.scalanativelib.api.", "sbt.testing.")
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

  override def close(): Unit = {
    // drop instance
    scalaInstanceCache = None
  }
}

object ScalaNativeWorkerApi extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
