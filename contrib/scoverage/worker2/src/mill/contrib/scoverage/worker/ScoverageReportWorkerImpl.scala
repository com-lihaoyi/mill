package mill.contrib.scoverage.worker

import _root_.scoverage.reporter.{
  CoberturaXmlWriter,
  CoverageAggregator,
  ScoverageHtmlWriter,
  ScoverageXmlWriter
}

import mill.contrib.scoverage.api.ScoverageReportWorkerApi2
import ScoverageReportWorkerApi2.ReportType
import ScoverageReportWorkerApi2.Ctx

import java.nio.file.Path

/**
 * Scoverage Worker for Scoverage 2.x
 */
class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi2 {

  override def report(
      reportType: ReportType,
      sources: Array[Path],
      dataDirs: Array[Path],
      sourceRoot: Path,
      ctx: Ctx
  ): Unit =
    try {
      ctx.log.info(s"Processing coverage data for ${dataDirs.size} data locations")
      CoverageAggregator.aggregate(dataDirs.map(_.toFile).toIndexedSeq, sourceRoot.toFile) match {
        case Some(coverage) =>
          val sourceFolders = sources.map(_.toFile).toIndexedSeq
          val folder = ctx.dest
          ScoverageReportWorkerApi2.makeAllDirs(folder)
          reportType match {
            case ReportType.Html =>
              new ScoverageHtmlWriter(sourceFolders, folder.toFile, None)
                .write(coverage)
            case ReportType.Xml =>
              new ScoverageXmlWriter(sourceFolders, folder.toFile, false, None)
                .write(coverage)
            case ReportType.XmlCobertura =>
              new CoberturaXmlWriter(sourceFolders, folder.toFile, None)
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
