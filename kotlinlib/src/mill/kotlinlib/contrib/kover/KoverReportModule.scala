/*
 * Some parts of this code are taken from lefou/mill-jacoco. Copyright 2021-Present Tobias Roeser.
 */

package mill
package kotlinlib.contrib.kover

import mill.api.Result.Success
import mill.api.{Loose, PathRef}
import mill.define.{Discover, ExternalModule}
import mill.eval.Evaluator
import mill.kotlinlib.contrib.kover.ReportType.Html
import mill.kotlinlib.{Dep, DepSyntax, Versions}
import mill.resolve.{Resolve, SelectMode}
import mill.scalalib.CoursierModule
import mill.scalalib.api.CompilationResult
import mill.util.Jvm
import os.Path

import java.util.Locale

trait KoverReportBaseModule extends CoursierModule {

  private[kover] val reportName = "kover-report"

  /**
   * Reads the Kover version from system environment variable `KOVER_VERSION` or defaults to a hardcoded version.
   */
  def koverVersion: T[String] = Task.Input {
    Success[String](T.env.getOrElse("KOVER_VERSION", Versions.koverVersion))
  }

  def koverCliDep: Target[Agg[Dep]] = T {
    Agg(ivy"org.jetbrains.kotlinx:kover-cli:${koverVersion()}")
  }

  /**
   * Classpath for running Kover.
   */
  def koverCliClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(koverCliDep())
  }
}

/**
 * Allows the aggregation of coverage reports across multi-module projects.
 *
 * Once tests have been run across all modules, this collects reports from
 * all modules that extend [[KoverModule]]. Simply
 * define a module that extends [[KoverReportModule]] and
 * call one of the available "report all" functions.
 *
 * For example, define the following `kover` module and use the relevant
 * reporting option to generate a report:
 * {{{
 * object kover extends KoverReportModule
 * }}}
 *
 * - mill __.test                 # run tests for all modules
 * - mill kover.htmlReportAll     # generates report in html format for all modules
 * - mill kover.xmlReportAll      # generates report in xml format for all modules
 *
 * The aggregated report will be available at either `out/kover/htmlReportAll.dest/`
 * for html reports or `out/kover/xmlReportAll.dest/` for xml reports.
 */
trait KoverReportModule extends CoursierModule with KoverReportBaseModule {

  def htmlReportAll(evaluator: Evaluator): Command[PathRef] = Task.Command {
    koverReportTask(
      evaluator = evaluator,
      reportType = ReportType.Html
    )()
  }

  def xmlReportAll(evaluator: Evaluator): Command[PathRef] = Task.Command {
    koverReportTask(
      evaluator = evaluator,
      reportType = ReportType.Xml
    )()
  }

  def koverReportTask(
      evaluator: mill.eval.Evaluator,
      sources: String = "__:KotlinModule:^TestModule.allSources",
      compiled: String = "__:KotlinModule:^TestModule.compile",
      binaryReports: String = "__.koverBinaryReport",
      reportType: ReportType = Html
  ): Task[PathRef] = {
    val sourcesTasks: Seq[Task[Seq[PathRef]]] = resolveTasks(sources, evaluator)
    val compiledTasks: Seq[Task[CompilationResult]] = resolveTasks(compiled, evaluator)
    val binaryReportTasks: Seq[Task[PathRef]] = resolveTasks(binaryReports, evaluator)

    Task.Anon {

      val sourcePaths: Seq[Path] =
        T.sequence(sourcesTasks)().flatten.map(_.path).filter(
          os.exists
        )
      val compiledPaths: Seq[Path] =
        T.sequence(compiledTasks)().map(_.classes.path).filter(
          os.exists
        )
      val binaryReportsPaths: Seq[Path] =
        T.sequence(binaryReportTasks)().map(_.path).filter(
          os.exists
        )

      val reportDir = PathRef(T.dest).path / reportName

      KoverReport.runKoverCli(
        sourcePaths,
        compiledPaths,
        binaryReportsPaths,
        reportDir,
        reportType,
        koverCliClasspath().map(_.path),
        T.dest
      )
      PathRef(reportDir)
    }
  }

  private def resolveTasks[T](tasks: String, evaluator: Evaluator): Seq[Task[T]] =
    if (tasks.trim().isEmpty) Seq.empty
    else Resolve.Tasks.resolve(evaluator.rootModule, Seq(tasks), SelectMode.Multi) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) => tasks.asInstanceOf[Seq[Task[T]]]
    }
}

private[kover] object KoverReport extends ExternalModule with KoverReportBaseModule {

  lazy val millDiscover: Discover = Discover[this.type]

  private[kover] def runKoverCli(
      sourcePaths: Seq[Path],
      compiledPaths: Seq[Path],
      binaryReportsPaths: Seq[Path],
      // will be treated as a dir in case of HTML, and as file in case of XML
      reportPath: Path,
      reportType: ReportType,
      classpath: Loose.Agg[Path],
      workingDir: os.Path
  )(implicit ctx: api.Ctx): Unit = {
    val args = Seq.newBuilder[String]
    args += "report"
    args ++= binaryReportsPaths.map(_.toString())

    args ++= sourcePaths.flatMap(path => Seq("--src", path.toString()))
    args ++= compiledPaths.flatMap(path => Seq("--classfiles", path.toString()))
    args ++= Seq(s"--${reportType.toString.toLowerCase(Locale.US)}", reportPath.toString())
    Jvm.runSubprocess(
      mainClass = "kotlinx.kover.cli.MainKt",
      classPath = classpath,
      jvmArgs = Seq.empty[String],
      mainArgs = args.result(),
      workingDir = workingDir
    )
  }
}

sealed trait ReportType
object ReportType {
  final case object Html extends ReportType
  final case object Xml extends ReportType
}
