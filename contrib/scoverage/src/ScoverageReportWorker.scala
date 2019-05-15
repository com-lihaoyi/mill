package mill.contrib.scoverage

import java.net.URLClassLoader

import mill.{Agg, T}
import mill.api.{Ctx, Result}
import mill.define.{Discover, ExternalModule, Worker}
import mill.eval.PathRef

class ScoverageReportWorker {
  private var scoverageInstanceCache = Option.empty[(Long, api.ScoverageReportWorkerApi)]

  def bridge(sources: Seq[PathRef], classpath: Agg[os.Path])
                    (implicit ctx: Ctx) = {
    val classloaderSig =
      classpath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scoverageInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val toolsClassPath = classpath.map(_.toIO.toURI.toURL).toVector
        ctx.log.debug("Loading classes from\n"+toolsClassPath.mkString("\n"))
        val cl = new URLClassLoader(toolsClassPath.toArray, null)
        val bridge = cl
          .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
          .getDeclaredConstructor()
          .newInstance(sources)
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
