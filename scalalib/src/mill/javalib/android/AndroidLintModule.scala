package mill.javalib.android

import mill._
import mill.api.PathRef
import mill.define.ModuleRef
import mill.scalalib.JavaModule

/**
 * Android Lint Module for integrating the Android Lint tool in a Mill build.
 *
 * This module provides configuration options for executing Android Lint, including setting paths,
 * specifying lint rules, managing reports, and more.
 */
@mill.api.experimental
trait AndroidLintModule extends JavaModule {

  /**
   * Abstract method to provide access to the Android SDK configuration.
   *
   * This method must be implemented by the concrete class to specify the SDK paths.
   *
   * @return The Android SDK module that is used across the project.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  /**
   * Specifies the file format of lint report. Available file formats are "html", "xml", "txt" and "sarif".
   */
  def androidLintReportFmt: T[String] = Task { "html" }

  /**
   * Specifies the lint configuration XML file path. This allows setting custom lint rules or modifying existing ones.
   */
  def androidLintConfigPath: T[Option[PathRef]] = Task { None }

  /**
   * SSpecifies the baseline file for Android Lint.
   */
  def androidLintBaselinePath: T[Option[PathRef]] = Task { None }

  /**
   * Runs the Android Lint tool to generate a report on code quality issues.
   *
   * This method utilizes Android Lint, a tool provided by the Android SDK,
   * to analyze the source code for potential bugs, performance issues, and
   * best practices compliance. It generates an HTML report by default with the analysis
   * results for review.
   *
   * The lint tool requires the Android SDK's command-line tools to be installed.
   * The report is saved in the task's destination directory as "report.html".
   *
   * For more details on the Android Lint tool, refer to:
   * [[https://developer.android.com/studio/write/lint]]
   */

  def androidLintRun: T[PathRef] = Task {

    val format = androidLintReportFmt()
    val lintReport: os.Path = T.dest / s"report.$format"

    // Map the report format to the corresponding lint command flag
    val lintReportFlag = format match {
      case "html" => "--html"
      case "txt" => "--text"
      case "xml" => "--xml"
      case "sarif" => "--sarif"
      case _ => throw new Exception(s"Unsupported report format: $format")
    }

    // Prepare the baseline argument if the baseline path is set
    val baselineArg = androidLintBaselinePath().map(baseline => Seq("--baseline", baseline.path.toString)).getOrElse(Seq.empty)

    // Prepare the lint configuration argument if the config path is set
    val configArg = androidLintConfigPath().map(config => Seq("--config", config.path.toString)).getOrElse(Seq.empty)

    os.call(
      Seq(
        androidSdkModule().cmdlineToolsPath().path.toString + "/lint",
        lintReportFlag.toString,
        lintReport.toString,
        millSourcePath.toString
      ) ++ configArg ++ baselineArg
    )

    PathRef(lintReport)
  }

}