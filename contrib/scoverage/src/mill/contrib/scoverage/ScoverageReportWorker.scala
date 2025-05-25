package mill.contrib.scoverage

import mill.Task
import mill.define.{TaskCtx, PathRef}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2
import mill.define.{Discover, ExternalModule}

import ScoverageReportWorker.ScoverageReportWorkerApiBridge
import ScoverageReportWorkerApi2.ReportType
import ScoverageReportWorkerApi2.{Logger => ApiLogger}
import ScoverageReportWorkerApi2.{Ctx => ApiCtx}

class ScoverageReportWorker {

  def bridge(classpath: Seq[PathRef])(implicit ctx: TaskCtx): ScoverageReportWorkerApiBridge = {
    def ctx0(implicit ctx: TaskCtx): ApiCtx = {
      val logger = new ApiLogger {
        def error(msg: String): Unit = ctx.log.error(msg)
        def warn(msg: String): Unit = ctx.log.warn(msg)
        def info(msg: String): Unit = ctx.log.info(msg)
        def debug(msg: String): Unit = ctx.log.debug(msg)
      }
      new ApiCtx {
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
          ctx: TaskCtx
      ): Unit = {
        mill.util.Jvm.withClassLoader(
          classpath.map(_.path).toVector,
          getClass.getClassLoader
        ) { cl =>

          val worker =
            cl
              .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
              .getDeclaredConstructor()
              .newInstance()
              .asInstanceOf[api.ScoverageReportWorkerApi2]

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
        ctx: TaskCtx
    ): Unit
  }

  def scoverageReportWorker: Task.Worker[ScoverageReportWorker] =
    Task.Worker { new ScoverageReportWorker() }
  lazy val millDiscover = Discover[this.type]
}
