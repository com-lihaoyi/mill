package mill.contrib.scoverage

import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType
import mill.define.{Command, Module, Task}
import mill.eval.Evaluator
import mill.main.{ResolveTasks, RunScript, SelectMode}
import mill.{PathRef, T}
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
      dataTargets: String = "__.scoverage.data"
  ): Command[PathRef] = T.command {
    reportTask(evaluator, ReportType.Html, sources, dataTargets)()
  }

  /** Generates report in xml format for all modules */
  def xmlReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTargets: String = "__.scoverage.data"
  ): Command[PathRef] = T.command {
    reportTask(evaluator, ReportType.Xml, sources, dataTargets)()
  }

  /** Reports to the console for all modules */
  def consoleReportAll(
      evaluator: Evaluator,
      sources: String = "__.allSources",
      dataTargets: String = "__.scoverage.data"
  ): Command[PathRef] = T.command {
    reportTask(evaluator, ReportType.Console, sources, dataTargets)()
  }

  def reportTask(
      evaluator: Evaluator,
      reportType: ReportType,
      sources: String,
      dataTargets: String
  ): Task[PathRef] = {
    val sourcesTasks: Seq[Task[Seq[PathRef]]] = ResolveTasks.resolve(
      evaluator,
      Seq(sources),
      SelectMode.Single
    ) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) => tasks.asInstanceOf[Seq[Task[Seq[PathRef]]]]
    }
    val dataTasks: Seq[Task[PathRef]] = ResolveTasks.resolve(
      evaluator,
      Seq(dataTargets),
      SelectMode.Single
    ) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) => tasks.asInstanceOf[Seq[Task[PathRef]]]
    }

    T.task {
      val sourcePaths: Seq[Path] = T.sequence(sourcesTasks)().flatten.map(_.path)
      val dataPaths: Seq[Path] = T.sequence(dataTasks)().map(_.path)
      scoverageReportWorkerModule
        .scoverageReportWorker()
        .bridge(workerModule.scoverageToolsClasspath())
        .report(reportType, sourcePaths, dataPaths, T.workspace)
      PathRef(T.dest)
    }
  }
}
