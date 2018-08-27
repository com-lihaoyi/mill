package mill.scalalib

import scoverage.{Constants => ScoverageConstants};

import mill.Cross
import mill.scalalib.{SbtModule, PublishModule, Dep, CrossSbtModule, DepSyntax}
import mill.scalalib.publish.{PomSettings, License, Developer, SCM}
import mill.define.TaskModule
import scala.sys.process.Process
import mill._, scalalib._
import mill.util.Logger
import ammonite.ops._
import java.io.File

import scoverage.IOUtils;
import scoverage.Serializer;
import scoverage.Coverage;
import scoverage.report.{
  CoverageAggregator,
  CoberturaXmlWriter,
  ScoverageHtmlWriter,
  ScoverageXmlWriter
}

/** A little trait to mix in within a module to add coverage.
  *
  * {{{
  * object coverage extends Tests with CoverageModule {
  *     def testIvyDeps = Agg(ivy"org.scalatest::scalatest:3.0.1")
  *     def testFrameworks = Seq("org.scalatest.tools.Framework")
  * }
  * }}}
  *
  * */
trait CoverageModule extends ScalaModule with CoverageOptions {
  import CoverageModule._
  import CoverageUtil._

  final override def ivyDeps =
    super.ivyDeps() ++ Agg(ivyScoverageRuntime) ++ testIvyDeps()

  def testIvyDeps = T { Agg.empty[Dep] }

  override def compileIvyDeps = super.compileIvyDeps() ++ Agg(ivyScoverageRuntime)

  override def scalacPluginIvyDeps =
    super.scalacPluginIvyDeps() ++ Agg(ivyScoveragePlugin)

  override def scalacOptions = T {
    val pluginPath = {
      val cp = scalacPluginClasspath()
      cp.find { pathRef =>
        val fileName = pathRef.path.toNIO.getFileName.toString
        fileName.toString.contains(ScalacPluginArtifact) && fileName.toString
          .contains(scoverageVersion)
      }
        .getOrElse {
          throw new Exception(
            s"Fatal: ${ScalacPluginArtifact} (${scoverageVersion}) not found on the classpath:\n\t" + cp
              .map(_.path)
              .mkString("\n\t"))
        }
    }.path
    super.scalacOptions() ++ Seq(
      Some(s"-Xplugin:${pluginPath}"),
      Some(s"-P:scoverage:dataDir:${coverageDataDir().toNIO}"),
      Option(excludedPackages).map(v => s"-P:scoverage:excludedPackages:$v"),
      Option(excludedFiles).map(v => s"-P:scoverage:excludedFiles:$v"),
      if (highlighting) Some("-Yrangepos") else None // rangepos is broken in some releases of scala so option to turn it off
    ).flatten
  }

  private def coverageRootDir = T {
    // FIXME: using artifactName is wrong
    val path = millSourcePath / 'out / artifactName()
      .split('-')
      .last / scalaVersion() / 'coverage / 'dest
    mkdir(path)
    path
  }

  private def coverageDataDir = T {
    val path = coverageRootDir() / 'data
    mkdir(path)
    path
  }

  def report = T {
    val log = T.ctx.log
    val dataDir = coverageDataDir().toNIO
    val rootDir = coverageRootDir().toNIO
    val encoding: Option[String] = sourceEncoding(scalacOptions())
    val compileSourceDirectories =
      sources().map(_.path.toNIO.toFile).filter(_.isDirectory)
    log.info("reporting...")
    loadCoverage(dataDir, log) match {
      case Some(cov) =>
        writeReports(rootDir,
          cov,
          this,
          compileSourceDirectories,
          encoding,
          log)
        checkCoverage(cov, log, this)
      case None => log.error("No coverage data, skipping reports")
    }
  }
}

trait AggregateCoverageModule extends Module with CoverageOptions {
  import CoverageUtil._

  def modules: Seq[TestModule]

  // FIXME: I want the sources!
  def sources = T { modules.flatMap { module => module.sources() } }

  def aggregate = T {
    val log = T.ctx.log
    log.info(s"Aggregating coverage from subprojects...")

    val rootDir = (millSourcePath / 'dest).toNIO
    val encoding: Option[String] = None // FIXME: sourceEncoding(scalacOptions())
    val compileSourceDirectories = sources().map(_.path.toNIO.toFile).filter(_.isDirectory)

    def getCoverageReports(dir: java.io.File): Seq[java.io.File] = {
      if (!dir.isDirectory()) Seq.empty else {
        val files = dir.listFiles
        files.filter(_.getName == ScoverageConstants.XMLReportFilename) ++
          files.filter(_.isDirectory).flatMap { d => getCoverageReports(d) }
      }
    }

    val xmlReportFiles = modules.flatMap{ module => getCoverageReports((module.millSourcePath / 'out).toNIO.toFile) }

    CoverageAggregator.aggregate(xmlReportFiles, cleanSubprojectFiles) match {
        case Some(cov) =>
          writeReports(rootDir, cov, this, compileSourceDirectories, encoding, log)
          val coverageFormatted = cov.statementCoverageFormatted
          log.info(s"Aggregation complete. Coverage was [$coverageFormatted]")

          checkCoverage(cov, log, this)
          case None =>
          log.info("No subproject data to aggregate, skipping reports")
      }
  }
}

object CoverageModule {
  val OrgScoverage = "org.scoverage"
  val ScalacRuntimeArtifact = "scalac-scoverage-runtime"
  val ScalacPluginArtifact = "scalac-scoverage-plugin"
  val DefaultScoverageVersion = "1.4.0-M3"
}

trait CoverageOptions {
  import CoverageModule._

  def scoverageVersion = DefaultScoverageVersion

  def ivyScoverageRuntime =
    ivy"${OrgScoverage}::${ScalacRuntimeArtifact}:${scoverageVersion}"

  def ivyScoveragePlugin =
    ivy"${OrgScoverage}::${ScalacPluginArtifact}:${scoverageVersion}"

  def excludedPackages: Seq[String] = Seq.empty

  def excludedFiles: Seq[String] = Seq.empty

  def highlighting: Boolean = true

  def minimum: Double = 0
  def failOnMinimum: Boolean = false
  def outputCobertura: Boolean = true
  def outputXML: Boolean = true
  def outputHTML: Boolean = true
  def outputDebug: Boolean = false
  //def outputTeamCity: Boolean = false

  // FIXME
  //def cleanSubprojectFiles: Boolean = true
  def cleanSubprojectFiles: Boolean = false
}

object CoverageUtil {

  def writeReports(rootDir: java.nio.file.Path,
                   coverage: Coverage,
                   options: CoverageOptions,
                   compileSourceDirectories: Seq[java.io.File],
                   encoding: Option[String],
                   log: Logger): Unit = {
    log.info(s"Generating scoverage reports...")

    val coberturaDir = rootDir.resolve("coverage-report").toFile
    val reportDir = rootDir.resolve("scoverage-report").toFile
    coberturaDir.mkdirs()
    reportDir.mkdirs()

    if (options.outputCobertura) {
      new CoberturaXmlWriter(compileSourceDirectories, coberturaDir)
        .write(coverage)
      log.info(
        s"Written Cobertura report [${coberturaDir.getAbsolutePath}/cobertura.xml]")
    }

    if (options.outputXML) {
      new ScoverageXmlWriter(compileSourceDirectories, reportDir, false)
        .write(coverage)
      if (options.outputDebug) {
        new ScoverageXmlWriter(compileSourceDirectories, reportDir, true)
          .write(coverage)
      }
      log.info(
        s"Written XML coverage report [${reportDir.getAbsolutePath}/scoverage.xml]")
    }

    if (options.outputHTML) {
      new ScoverageHtmlWriter(compileSourceDirectories, reportDir, encoding)
        .write(coverage)
      log.info(
        s"Written HTML coverage report [${reportDir.getAbsolutePath}/index.html]")
    }

    // FIXME: team city
    /*
       if (options.outputTeamCity) {
       reportToTeamcity(coverage, outputHTML, reportDir, log)
       log.info("Written coverage report to TeamCity")
       }
     */

    log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
    log.info(s"Branch coverage....: ${coverage.branchCoverageFormatted}%")
    log.info("Coverage reports completed")
  }

  def loadCoverage(dataDir: java.nio.file.Path,
                   log: Logger): Option[Coverage] = {
    val coverageFile = Serializer.coverageFile(dataDir.toFile)
    if (coverageFile.exists) {
      log.info(s"Reading scoverage instrumentation [$coverageFile]")
      val coverage = Serializer.deserialize(coverageFile)
      log.info(s"Reading scoverage measurements...")
      val measurementFiles = IOUtils.findMeasurementFiles(dataDir.toFile)
      val measurements = IOUtils.invoked(measurementFiles)
      coverage.apply(measurements)
      Some(coverage)
    } else {
      log.error(s"Scoverage instrumentation not found [$coverageFile]")
      None
    }
  }

  def checkCoverage(coverage: Coverage,
                    log: Logger,
                    options: CoverageOptions): Unit = {

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

  def sourceEncoding(scalacOptions: Seq[String]): Option[String] = {
    val i = scalacOptions.indexOf("-encoding") + 1
    if (i > 0 && i < scalacOptions.length) Some(scalacOptions(i)) else None
  }

  /*
  private def reportToTeamcity(coverage: Coverage,
  dataDir: java.nio.file.Path,
  createCoverageZip: Boolean,
  reportDir: File,
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

