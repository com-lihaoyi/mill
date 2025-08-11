package mill.contrib.scoverage

import mill.api.Result
import mill.contrib.scoverage.api.ScoverageReportWorkerApi2.ReportType
import mill.api.{Evaluator, Module, SelectMode, Task}
import mill.api.BuildCtx
import mill.*
import os.Path

/**
 * Allows the aggregation of coverage reports across multi-module projects.
 *
 * Once tests have been run across all modules, this collects reports from
 * all modules that extend [[mill.contrib.scoverage.ScoverageModule]]. Simply
 * define a module that extends [[mill.contrib.scoverage.ScoverageReport]] and
 * call one of the available "report all" functions.
 *
 * For example, define the following `scoverage` module and use the relevant
 * reporting option to generate a report:
 * {{{
 * object scoverage extends ScoverageReport {
 *   override def scalaVersion     = "<scala-version>"
 *   override def scoverageVersion = "<scoverage-version>"
 * }
 * }}}
 *
 * - mill __.test                     # run tests for all modules
 * - mill scoverage.htmlReportAll     # generates report in html format for all modules
 * - mill scoverage.xmlReportAll      # generates report in xml format for all modules
 * - mill scoverage.xmlCoberturaReportAll  # generates report in Cobertura's xml format for all modules
 * - mill scoverage.consoleReportAll  # reports to the console for all modules
 *
 * The aggregated report will be available at either `out/scoverage/htmlReportAll.dest/`
 * for html reports or `out/scoverage/xmlReportAll.dest/` for xml reports.
 */
trait ScoverageReport extends Module {
  outer =>
  def scalaVersion: T[String]

  def scoverageVersion: T[String]

  def scoverageReportWorkerModule: ScoverageReportWorker.type = ScoverageReportWorker

  /** We use this only to get access to the right classpaths */
  object workerModule extends ScoverageModule {
    override def scalaVersion = outer.scalaVersion

    override def scoverageVersion = outer.scoverageVersion
  }

  /** Generates report in html format for all modules */
  def htmlReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTasks: String = "__.scoverage.data"
  ): Command[PathRef] = Task.Command(exclusive = true) {
    reportTask(evaluator, ReportType.Html, sources, dataTasks)()
  }

  /** Generates report in xml format for all modules */
  def xmlReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTasks: String = "__.scoverage.data"
  ): Command[PathRef] = Task.Command(exclusive = true) {
    reportTask(evaluator, ReportType.Xml, sources, dataTasks)()
  }

  /** Generates report in Cobertura's xml format for all modules */
  def xmlCoberturaReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTasks: String = "__.scoverage.data"
  ): Command[PathRef] = Task.Command(exclusive = true) {
    reportTask(evaluator, ReportType.XmlCobertura, sources, dataTasks)()
  }

  /** Reports to the console for all modules */
  def consoleReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTasks: String = "__.scoverage.data"
  ): Command[PathRef] = Task.Command(exclusive = true) {
    reportTask(evaluator, ReportType.Console, sources, dataTasks)()
  }

  def reportTask(
      evaluator: Evaluator,
      reportType: ReportType,
      sources: String,
      dataTaskStrings: String
  ): Task[PathRef] = {
    val sourcesTasks: Seq[Task[Seq[PathRef]]] = evaluator.resolveTasks(
      Seq(sources),
      SelectMode.Separated
    ).get.asInstanceOf[Seq[Task[Seq[PathRef]]]]

    val dataTasks: Seq[Task[PathRef]] = evaluator.resolveTasks(
      Seq(dataTaskStrings),
      SelectMode.Separated
    ).get.asInstanceOf[Seq[Task[PathRef]]]

    Task.Anon {
      val sourcePaths: Seq[Path] = Task.sequence(sourcesTasks)().flatten.map(_.path)
      val dataPaths: Seq[Path] = Task.sequence(dataTasks)().map(_.path)
      scoverageReportWorkerModule
        .scoverageReportWorker()
        .bridge(workerModule.scoverageToolsClasspath())
        .report(reportType, sourcePaths, dataPaths, BuildCtx.workspaceRoot)
      PathRef(Task.dest)
    }
  }
}
