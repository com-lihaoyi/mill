package mill.contrib.scoverage.worker

import _root_.scoverage.reporter.{
  CoberturaXmlWriter,
  CoverageAggregator,
  ScoverageHtmlWriter,
  ScoverageXmlWriter
}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2.{Ctx, ReportType}
import scoverage.domain.Coverage

import java.lang
import java.nio.file.Path

/**
 * Scoverage Worker for Scoverage 2.x
 */
class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi2 {

  /**
   * Validates whether the project's code coverage meets specified minimum thresholds for both statement and branch
   * coverage.
   *
   *  This method processes coverage data from specified directories to ensure that both statement and branch coverage
   *  meet or exceed the provided thresholds. It aggregates coverage metrics using Scoverage's CoverageAggregator and
   *  checks each metric against its respective minimum.
   *
   * If either coverage threshold is not met, a RuntimeException is thrown with detailed error messages indicating
   * which thresholds were violated.
   *
   * If no coverage data is found in the specified directories, a warning is logged but no exception is thrown.
   *
   * @param sources              Array of paths representing the source files or directories being analyzed.
   * @param dataDirs             Array of paths pointing to directories containing Scoverage coverage data.
   * @param sourceRoot           The root directory from which relative paths are determined, typically the project's workspace root.
   * @param statementCoverageMin The minimum acceptable percentage for statement coverage. If not met, an exception is thrown.
   * @param branchCoverageMin    The minimum acceptable percentage for branch coverage. If not met, an exception is thrown.
   * @param ctx                  A context object providing logging and destination directory information for the worker's operations.
   */
  override def validateCoverageMinimums(
      dataDirs: Array[Path],
      sourceRoot: Path,
      statementCoverageMin: lang.Double,
      branchCoverageMin: lang.Double,
      ctx: Ctx
  ): Unit = {
    try {
      ctx.log.info(s"Processing coverage data for ${dataDirs.size} data locations")

      // Attempt to aggregate coverage data
      val aggregatedCoverage = CoverageAggregator.aggregate(
        dataDirs.map(_.toFile).toIndexedSeq,
        sourceRoot.toFile
      )

      aggregatedCoverage match {
        case Some(currentCoverages) =>
          verifyThatCoverageMinsAreMet(
            statementCoverageMin,
            branchCoverageMin,
            ctx,
            currentCoverages
          )
        case None =>
          // Log warning if no coverage data found
          ctx.log.warn(s"No coverage data found in [${dataDirs.mkString(", ")}]")
      }

    } catch {
      case e: Throwable =>
        ctx.log.error(s"Exception while validating coverage minimums. ${e.getMessage()}")
        throw e
    }
  }

  private def verifyThatCoverageMinsAreMet(
      statementCoverageMin: lang.Double,
      branchCoverageMin: lang.Double,
      ctx: Ctx,
      currentCoverages: Coverage
  ): Unit = {
    // Check statement coverage
    val statementError =
      Option.when(currentCoverages.statementCoverage < statementCoverageMin)(
        s"This project's statement coverage (${currentCoverages.statementCoverage}) did not meet the minimum desired by the project. ($statementCoverageMin)"
      )

    // Check branch coverage
    val branchError = Option.when(currentCoverages.branchCoverage < branchCoverageMin)(
      s"This project's branch coverage (${currentCoverages.branchCoverage}) did not meet the minimum desired by the project. ($branchCoverageMin)"
    )

    // Collect all errors
    val errors = (statementError.toList ++ branchError.toList)

    if (errors.nonEmpty) {
      // Throw exception with all error messages
      throw new RuntimeException(
        s"Coverage minimums violated:\n${errors.mkString("\n")}"
      )
    } else {
      ctx.log.info(s"Statement coverage: ${currentCoverages.statementCoverageFormatted}%")
      ctx.log.info(s"Branch coverage....: ${currentCoverages.branchCoverageFormatted}%")
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
