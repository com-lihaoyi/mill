package mill.contrib.scoverage

import mill.Task
import mill.api.{Discover, ExternalModule, PathRef, TaskCtx}
import mill.contrib.scoverage.ScoverageReportWorker.ScoverageReportWorkerApiBridge
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2.{
  ReportType,
  Ctx as ApiCtx,
  Logger as ApiLogger
}
import os.Path

class ScoverageReportWorker {

  def bridge(classpath: Seq[PathRef]): ScoverageReportWorkerApiBridge = {
    def ctx0(using ctx: TaskCtx): ApiCtx = {
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
      private def innerWorker[T](worker: ScoverageReportWorkerApi2 => T) = {
        mill.util.Jvm.withClassLoader(
          classpath.map(_.path).toVector,
          getClass.getClassLoader
        ) { cl =>
          val a = cl
            .loadClass("mill.contrib.scoverage.worker.ScoverageReportWorkerImpl")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[api.ScoverageReportWorkerApi2]

          worker(a)
        }
      }

      override def report(
          reportType: ReportType,
          sources: Seq[os.Path],
          dataDirs: Seq[os.Path],
          sourceRoot: os.Path
      )(using
          ctx: TaskCtx
      ): Unit = {
        innerWorker(_.report(
          reportType,
          sources.map(_.toNIO).toArray,
          dataDirs.map(_.toNIO).toArray,
          sourceRoot.toNIO,
          ctx0
        ))
      }

      override def validateCoverageMinimums(
          sources: Seq[Path],
          dataDirs: Seq[Path],
          sourceRoot: Path,
          statementCoverageMin: Double,
          branchCoverageMin: Double
      )(using ctx: TaskCtx): Unit = innerWorker(_.validateCoverageMinimums(
        sources.map(_.toNIO).toArray,
        dataDirs.map(_.toNIO).toArray,
        sourceRoot.toNIO,
        statementCoverageMin,
        branchCoverageMin,
        ctx0
      ))
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
    )(using
        ctx: TaskCtx
    ): Unit

    def validateCoverageMinimums(
        sources: Seq[os.Path],
        dataDirs: Seq[os.Path],
        sourceRoot: os.Path,
        statementCoverageMin: Double,
        branchCoverageMin: Double
    )(using
        ctx: TaskCtx
    ): Unit
  }

  def scoverageReportWorker: Task.Worker[ScoverageReportWorker] =
    Task.Worker { new ScoverageReportWorker() }
  lazy val millDiscover = Discover[this.type]
}
