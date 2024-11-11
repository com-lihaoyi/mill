package mill.javalib.android

import mill._
import mill.api.PathRef
import mill.define.ModuleRef
import mill.scalalib.JavaModule
import mill.javalib.android.AndroidSdkModule

/**
 * Android Lint Module for integrating the Android Lint tool in a Mill build.
 *
 * This module provides configuration options for executing Android Lint, including setting paths,
 * specifying lint rules, managing reports, and more.
 */
@mill.api.experimental
trait AndroidLintModule extends JavaModule {

  /**
   * Path to the project for which lint should run.
   */
  def projectPath: T[PathRef] = Task.Source { millSourcePath }

  /**
   * Specifies the file format of lint report.
   */
  def lintReportFormat: T[String] = Task { "html" }

  /**
   * Specifies the lint configuration XML file path. This allows setting custom lint rules or modifying existing ones.
   */
  def lintConfigPath: T[Option[PathRef]] = Task { None }

  /**
   * Enable or disable warnings in the lint report.
   */
  def warningsAsErrors: T[Boolean] = Task { false }

  /**
   * Additional options for lint (e.g., enabling/disabling specific checks).
   */
  def lintOptions: T[Seq[String]] = Task { Seq("--check", "NewApi,InlinedApi") }

  /**
   * Runs the Android Lint tool to generate a report on code quality issues.
   *
   * This method utilizes Android Lint, a tool provided by the Android SDK,
   * to analyze the source code for potential bugs, performance issues, and
   * best practices compliance. It generates an HTML report with the analysis
   * results for review.
   *
   * The lint tool requires the Android SDK's command-line tools to be installed.
   * The report is saved in the task's destination directory as "report.html".
   *
   * For more details on the Android Lint tool, refer to:
   * [[https://developer.android.com/studio/write/lint]]
   */

  def androidLint: T[PathRef] = Task {

    val format = lintReportFormat()
    val lintReport: os.Path = T.dest / s"report.$format"

    // Map the report format to the corresponding lint command flag
    val lintReportFlag = format match {
      case "html" => "--html"
      case "txt" => "--text"
      case "xml" => "--xml"
      case _ => throw new Exception(s"Unsupported report format: $format")
    }

    os.call(
      Seq(
        androidSdkModule().cmdlineToolsPath().path.toString + "/lint",
        lintReportFlag,
        lintReport.toString,
        projectPath.toString
      )
    )

    PathRef(lintReport)
  }

}
