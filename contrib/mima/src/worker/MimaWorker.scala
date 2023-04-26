package mill.mima.worker

import mill.mima.worker.api.MimaWorkerApi
import mill.Agg
import mill.PathRef
import mill.T
import mill.define.Discover
import mill.define.Worker

class MimaWorker {
  private var scalaInstanceCache = Option.empty[(Long, MimaWorkerApi)]

  def impl(
            mimaWorkerClasspath: Agg[PathRef]
          )(implicit ctx: mill.api.Ctx.Home): MimaWorkerApi = {
    val classloaderSig = mimaWorkerClasspath.hashCode
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          mimaWorkerClasspath.map(_.path.toIO.toURI.toURL).iterator.to(Seq),
          parent = null,
          sharedLoader = getClass.getClassLoader,
          sharedPrefixes = Seq("mill.mima.worker.api.")
        )
        try {
          val bridge = cl
            .loadClass(
              "mill.mima.worker.MimaWorkerImpl"
            )
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[
            mill.mima.worker.api.MimaWorkerApi
          ]
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

object MimaWorkerExternalModule extends mill.define.ExternalModule {
  def mimaWorker: Worker[MimaWorker] = T.worker {
    new MimaWorker()
  }
  lazy val millDiscover = Discover[this.type]
}
