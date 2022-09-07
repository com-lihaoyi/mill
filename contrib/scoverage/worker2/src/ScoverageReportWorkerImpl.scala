package mill.contrib.scoverage.worker

import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import _root_.scoverage.reporter.{CoverageAggregator, ScoverageHtmlWriter, ScoverageXmlWriter}
import mill.api.Ctx
import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType

/**
 * Scoverage Worker for Scoverage 1.x
 */
class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi {

  override def report(
      reportType: ReportType,
      sources: Seq[os.Path],
      dataDirs: Seq[os.Path],
      sourceRoot: os.Path
  )(implicit ctx: Ctx): Unit =
    try {
      ctx.log.info(s"Processing coverage data for ${dataDirs.size} data locations")
      CoverageAggregator.aggregate(dataDirs.map(_.toIO), sourceRoot.toIO) match {
        case Some(coverage) =>
          val sourceFolders = sources.map(_.toIO)
          val folder = ctx.dest
          os.makeDir.all(folder)
          reportType match {
            case ReportType.Html =>
              new ScoverageHtmlWriter(sourceFolders, folder.toIO, None)
                .write(coverage)
            case ReportType.Xml =>
              new ScoverageXmlWriter(sourceFolders, folder.toIO, false, None)
                .write(coverage)
            case ReportType.Console =>
              ctx.log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
              ctx.log.info(s"Branch coverage....: ${coverage.branchCoverageFormatted}%")
          }
        case None =>
          ctx.log.error(s"No coverage data found in [${dataDirs.mkString(", ")}]")
      }
    } catch {
      case e: Throwable =>
        ctx.log.error(s"Exception while building coverage report. ${e.getMessage()}")
        e.printStackTrace()
        throw e
    }
}
