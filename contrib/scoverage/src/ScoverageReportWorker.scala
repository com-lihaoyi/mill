package mill.contrib.scoverage

import mill.{Agg, T}
import mill.api.{ClassLoader, Ctx}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import mill.define.{Discover, ExternalModule, Worker}

class ScoverageReportWorker {
  private var scoverageInstanceCache = Option.empty[(Long, api.ScoverageReportWorkerApi)]

  def bridge(classpath: Agg[os.Path])
                    (implicit ctx: Ctx): ScoverageReportWorkerApi = {
    val classloaderSig =
      classpath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scoverageInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val toolsClassPath = classpath.map(_.toIO.toURI.toURL).toVector
        ctx.log.debug("Loading classes from\n"+toolsClassPath.mkString("\n"))
        val cl = ClassLoader.create(
          toolsClassPath,
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

object ScoverageReportWorker extends ExternalModule {

  def scoverageReportWorker: Worker[ScoverageReportWorker] = T.worker { new ScoverageReportWorker() }
  lazy val millDiscover = Discover[this.type]
}
