package mill.contrib.scoverage

import mill.{Agg, T}
import mill.api.{ClassLoader, Ctx, PathRef}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import mill.define.{Discover, ExternalModule, Worker}

class ScoverageReportWorker extends AutoCloseable {
  private[this] var scoverageClCache = Option.empty[(Long, ClassLoader)]

  def bridge(classpath: Agg[PathRef])(implicit ctx: Ctx): ScoverageReportWorkerApi = {
    val klassName = "mill.contrib.scoverage.worker.ScoverageReportWorkerImpl"
    val classloaderSig = classpath.hashCode
    val cl = scoverageClCache match {
      case Some((sig, cl)) if sig == classloaderSig => cl
      case _ =>
        val toolsClassPath = classpath.map(_.path.toIO.toURI.toURL).toVector
        ctx.log.debug("Loading worker classes from\n" + toolsClassPath.mkString("\n"))
        val cl = ClassLoader.create(
          toolsClassPath,
          getClass.getClassLoader
        )
        scoverageClCache = Some((classloaderSig, cl))
        cl
    }

    cl
      .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[api.ScoverageReportWorkerApi]
  }

  override def close(): Unit = {
    scoverageClCache = None
  }
}

object ScoverageReportWorker extends ExternalModule {

  def scoverageReportWorker: Worker[ScoverageReportWorker] =
    T.worker { new ScoverageReportWorker() }
  lazy val millDiscover = Discover[this.type]
}
