package mill.scalanativelib.worker

import mill.define.{Discover, Worker}
import mill.{Agg, PathRef, T}
import mill.scalanativelib.worker.{api => workerApi}

private[scalanativelib] class ScalaNativeWorker extends AutoCloseable {
  private var scalaInstanceCache = Option.empty[(Long, workerApi.ScalaNativeWorkerApi)]

  def bridge(toolsClasspath: Agg[PathRef])(implicit
      ctx: mill.api.Ctx.Home
  ): workerApi.ScalaNativeWorkerApi = {
    val classloaderSig = toolsClasspath.hashCode
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          toolsClasspath.iterator.map(_.path.toIO.toURI.toURL).toVector,
          getClass.getClassLoader
        )
        try {
          val bridge = cl
            .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[workerApi.ScalaNativeWorkerApi]
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

private[scalanativelib] object ScalaNativeWorkerExternalModule extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
