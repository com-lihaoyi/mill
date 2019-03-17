package mill.contrib.scoverage

import mill.{Agg, T}
import mill.api.{ClassLoader, Ctx, Result}
import mill.define.{Discover, ExternalModule, Worker}

class ScoverageReportWorker {
  private var scoverageInstanceCache = Option.empty[(Long, api.ScoverageReportWorkerApi)]

  def bridge(classpath: Agg[os.Path])
                    (implicit ctx: Ctx) = {
    val classloaderSig =
      classpath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scoverageInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = ClassLoader.create(
          classpath.map(_.toIO.toURI.toURL).toVector,
          getClass.getClassLoader
        )
        val bridge = cl
          .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[api.ScoverageReportWorkerApi]
        scoverageInstanceCache = Some((classloaderSig, bridge))
        bridge
    }
  }
}

object ScoverageReportWorkerApi extends ExternalModule {

  def scoverageReportWorker = T.worker { new ScoverageReportWorker() }
  lazy val millDiscover = Discover[this.type]
}
