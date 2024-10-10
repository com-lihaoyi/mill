package mill.contrib.scoverage

import mill.{Agg, Task}
import mill.api.{ClassLoader, Ctx, PathRef}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2
import mill.define.{Discover, ExternalModule, Worker}

import ScoverageReportWorker.ScoverageReportWorkerApiBridge
import ScoverageReportWorkerApi2.ReportType
import ScoverageReportWorkerApi2.Logger as ApiLogger
import ScoverageReportWorkerApi2.Ctx as ApiCtx

class ScoverageReportWorker extends AutoCloseable {
  private var scoverageClCache = Option.empty[(Long, ClassLoader)]

  def bridge(classpath: Agg[PathRef])(implicit ctx: Ctx): ScoverageReportWorkerApiBridge = {

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

    val worker =
      cl
        .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[api.ScoverageReportWorkerApi2]

    def ctx0(using ctx: Ctx): ApiCtx = {
      val logger = new ApiLogger {
        def info(msg: String): Unit = ctx.log.info(msg)
        def error(msg: String): Unit = ctx.log.error(msg)
        def debug(msg: String): Unit = ctx.log.debug(msg)
      }
      new {
        def log() = logger
        def dest() = ctx.dest.toNIO
      }
    }

    new ScoverageReportWorkerApiBridge {
      override def report(
          reportType: ReportType,
          sources: Seq[os.Path],
          dataDirs: Seq[os.Path],
          sourceRoot: os.Path
      )(implicit
          ctx: Ctx
      ): Unit = {
        worker.report(
          reportType,
          sources.map(_.toNIO).toArray,
          dataDirs.map(_.toNIO).toArray,
          sourceRoot.toNIO,
          ctx0
        )
      }
    }
  }

  override def close(): Unit = {
    scoverageClCache = None
  }
}

object ScoverageReportWorker extends ExternalModule {
  import ScoverageReportWorkerApi2.ReportType

  trait ScoverageReportWorkerApiBridge {
    def report(
        reportType: ReportType,
        sources: Seq[os.Path],
        dataDirs: Seq[os.Path],
        sourceRoot: os.Path
    )(implicit
        ctx: Ctx
    ): Unit
  }

  def scoverageReportWorker: Worker[ScoverageReportWorker] =
    Task.Worker { new ScoverageReportWorker() }
  lazy val millDiscover: Discover = Discover[this.type]
}
