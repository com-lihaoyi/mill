package mill.scalacoverage.worker

import ammonite.ops.Path
import mill.scalacoverage.{ScoverageOptions, ScoverageWorkerApi}
import mill.util.Logger
import scoverage.report.{CoberturaXmlWriter, CoverageAggregator, ScoverageHtmlWriter, ScoverageXmlWriter}
import scoverage.{Constants, Coverage, IOUtils, Serializer}


class ScoverageWorkerImpl extends ScoverageWorkerApi {

  def coverageReport(options: ScoverageOptions,
                     dataDir: Path,
                     reportDir: Path,
                     compileSourceDirectories: Seq[Path],
                     encoding: Option[String],
                     log: Logger): Unit = {
    log.info("reporting...")
    loadCoverage(dataDir, log) match {
      case Some(cov) =>
        writeReports(
          options                  = options,
          reportDir                = reportDir.toNIO,
          coverage                 = cov,
          compileSourceDirectories = compileSourceDirectories.map(_.toNIO.toFile),
          encoding                 = encoding,
          log                      = log)
        checkCoverage(options, cov, log)
      case None => log.error("No coverage data, skipping reports")
    }
  }

  def aggregateCoverage(options: ScoverageOptions,
                        coverageReportDirs: Seq[Path],
                        aggregateReportDir: Path,
                        compileSourceDirectories: Seq[Path],
                        encoding: Option[String],
                        log: Logger): Unit = {
    def getCoverageReports(dir: java.io.File): Seq[java.io.File] = {
      if (!dir.isDirectory) Seq.empty else {
        val files = dir.listFiles
        files.filter(_.getName == Constants.XMLReportFilename) ++
          files.filter(_.isDirectory).flatMap { d => getCoverageReports(d) }
      }
    }
    val xmlReportFiles = coverageReportDirs.flatMap { dir => getCoverageReports(dir.toNIO.toFile) }
    CoverageAggregator.aggregate(xmlReportFiles, options.cleanSubprojectFiles) match {
      case None      => log.info("No subproject data to aggregate, skipping reports")
      case Some(cov) =>
        writeReports(
          options                  = options,
          reportDir                = aggregateReportDir.toNIO,
          coverage                 = cov,
          compileSourceDirectories = compileSourceDirectories.map(_.toNIO.toFile),
          encoding                 = encoding,
          log                      = log)
        val coverageFormatted = cov.statementCoverageFormatted
        log.info(s"Aggregation complete. Coverage was [$coverageFormatted]")
        checkCoverage(options, cov, log)
    }
  }

  /** Loads the coverage data from the given data directory.
    *
    * @param dataDir the directory with coverage data
    * @param log the logger to write status information.
    * @return the coverage information, or None if the coverage file could not be found.
    */
  private def loadCoverage(dataDir: Path,
                           log: Logger): Option[Coverage] = {
    val coverageFile = Serializer.coverageFile(dataDir.toNIO.toFile)
    if (coverageFile.exists) {
      log.info(s"Reading scoverage instrumentation [$coverageFile]")
      val coverage = Serializer.deserialize(coverageFile)
      log.info(s"Reading scoverage measurements...")
      val measurementFiles = IOUtils.findMeasurementFiles(dataDir.toNIO.toFile)
      val measurements = IOUtils.invoked(measurementFiles)
      coverage.apply(measurements)
      Some(coverage)
    } else {
      log.error(s"Scoverage instrumentation not found [$coverageFile]")
      None
    }
  }

  /** Logs and optionally checks the coverage.
    *
    * If the coverage minimum is greater than zero, logs the coverage relative to the minimum.  If the
    * [[ScoverageOptions.failOnMinimum]] option is set, then it will throw a [[RuntimeException]] if the coverage is
    * below the minimum.
    *
    * @param options the various coverage options
    * @param coverage the coverage data to check
    * @param log the logger to write coverage status.
    */
  private def checkCoverage(options: ScoverageOptions,
                            coverage: Coverage,
                            log: Logger): Unit = {

    val coveragePercent = coverage.statementCoveragePercent
    val coverageFormatted = coverage.statementCoverageFormatted

    // check for default minimum
    if (options.minimum > 0) {
      def is100(d: Double) = Math.abs(100 - d) <= 0.00001

      if (is100(options.minimum) && is100(coveragePercent)) {
        log.info(s"100% Coverage !")
      } else if (options.minimum > coveragePercent) {
        log.error(
          s"Coverage is below minimum [$coverageFormatted% < ${options.minimum}%]")
        if (options.failOnMinimum)
          throw new RuntimeException("Coverage minimum was not reached")
      } else {
        log.info(
          s"Coverage is above minimum [$coverageFormatted% > ${options.minimum}%]")
      }
    }

    log.info(s"All done. Coverage was [$coverageFormatted%]")
  }

  /** Writes the desired coverage reports based on the options:
    * - [[ScoverageOptions.outputCobertura]]
    * - [[ScoverageOptions.outputXML]]
    * - [[ScoverageOptions.outputDebug]]
    * - [[ScoverageOptions.outputHTML]]
    *
    * Will write the reports to the given report directory.
    *
    * @param options the coverage options, used to determine which reports to write.
    * @param reportDir the directory to which reports should be written.
    * @param coverage the coverage information to write.
    * @param compileSourceDirectories the source directories.
    * @param encoding optionally the encoding for the HTML output.
    * @param log the logger to write status information.
    */
  private def writeReports(options: ScoverageOptions,
                           reportDir: java.nio.file.Path,
                           coverage: Coverage,
                           compileSourceDirectories: Seq[java.io.File],
                           encoding: Option[String],
                           log: Logger): Unit = {
    log.info(s"Generating scoverage reports...")

    val coberturaDir = reportDir.resolve("coverage-report").toFile
    val scoverageDir = reportDir.resolve("scoverage-report").toFile

    if (options.outputCobertura) {
      coberturaDir.mkdirs()
      new CoberturaXmlWriter(compileSourceDirectories, coberturaDir)
        .write(coverage)
      log.info(
        s"Written Cobertura report [${coberturaDir.getAbsolutePath}/cobertura.xml]")
    }

    if (options.outputXML) {
      scoverageDir.mkdirs()
      new ScoverageXmlWriter(compileSourceDirectories, scoverageDir, false)
        .write(coverage)
      if (options.outputDebug) {
        new ScoverageXmlWriter(compileSourceDirectories, scoverageDir, true)
          .write(coverage)
      }
      log.info(
        s"Written XML coverage report [${scoverageDir.getAbsolutePath}/scoverage.xml]")
    }

    if (options.outputHTML) {
      scoverageDir.mkdirs()
      new ScoverageHtmlWriter(compileSourceDirectories, scoverageDir, encoding)
        .write(coverage)
      log.info(
        s"Written HTML coverage report [${scoverageDir.getAbsolutePath}/index.html]")
    }

    // FIXME: team city
    /*
       if (options.outputTeamCity) {
       scoverageDir.mkdirs()
       reportToTeamcity(coverage, outputHTML, scoverageDir, log)
       log.info("Written coverage report to TeamCity")
       }
     */

    log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
    log.info(s"Branch coverage....: ${coverage.branchCoverageFormatted}%")
    log.info("Coverage reports completed")
  }

  /*
  private def reportToTeamcity(coverage: Coverage,
  dataDir: java.nio.file.Path,
  createCoverageZip: Boolean,
  reportDir: java.io.File,
  log: Logger) {
  def statsKeyValue(key: String, value: Int): String = s"##teamcity[buildStatisticValue key='$key' value='$value']"

  // Log statement coverage as per: https://devnet.jetbrains.com/message/5467985
  log.info(statsKeyValue("CodeCoverageAbsSCovered", coverage.invokedStatementCount))
  log.info(statsKeyValue("CodeCoverageAbsSTotal", coverage.statementCount))

  // Log branch coverage as a custom metrics (in percent)
  log.info(statsKeyValue("CodeCoverageBranch", "%.0f".format(coverage.branchCoveragePercent).toInt))

  // Create the coverage report for teamcity (HTML files)
  if (createCoverageZip)
  IO.zip(java.nio.file.Path.allSubpaths(reportDir), dataDir.resolve("coverage.zip").toFile)
  }
  */
}
