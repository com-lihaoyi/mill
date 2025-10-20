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

  /**
   * @param sources
   * @param dataDirs
   * @param sourceRoot
   * @param statementCoverageMin This is a nullalble expectation of the statement coverage minimum.
   * @param branchCoverageMin This is a nullalble expectation of the branch coverage minimum.
   * @param ctx
   */
  override def validateCoverageMinimums(
      sources: Array[Path],
      dataDirs: Array[Path],
      sourceRoot: Path,
      statementCoverageMin: Double,
      branchCoverageMin: Double,
      ctx: Ctx
  ): Unit = {
    try {
      ctx.log.info(s"Processing coverage data for ${dataDirs.size} data locations")
      CoverageAggregator.aggregate(dataDirs.map(_.toFile).toIndexedSeq, sourceRoot.toFile).map(
        cov => (cov.statementCoverage, cov.branchCoverage))
        
        //TODO: Create a warning and an exception that there is no coverage despite it being requested
        //TODO: Break if the statementCOverageMin was not met 
        //TODO: Break if the statementCoverageMin was not met
        //TODO: Write out the results before breaking
        //TODO: If both were met then do a console write 
        
     /* ) match {
        case Some(coverage) => {
          ctx.log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
          ctx.log.info(s"Branch coverage....: ${coverage.branchCoverageFormatted}%")
        }
        case None =>
          ctx.log.warn(s"No coverage data found in [${dataDirs.mkString(", ")}]")
      }*/
    } catch {
      case e: Throwable =>
        ctx.log.error(s"Exception while building coverage report. ${e.getMessage()}")
        e.printStackTrace()
        throw e
    }
  }
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
          ctx.log.warn(s"No coverage data found in [${dataDirs.mkString(", ")}]")
      }
    } catch {
      case e: Throwable =>
        ctx.log.error(s"Exception while building coverage report. ${e.getMessage()}")
        e.printStackTrace()
        throw e
    }
}
