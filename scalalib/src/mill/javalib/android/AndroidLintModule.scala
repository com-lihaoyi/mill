package mill.javalib.android

import mill._
import mill.api.PathRef
import mill.javalib.android.AndroidAppModule

/**
 * Android Lint Module for integrating the Android Lint tool in a Mill build.
 *
 * This module provides configuration options for executing Android Lint,
 * including custom arguments through `androidLintArgs`.
 */
@mill.api.experimental
trait AndroidLintModule extends AndroidAppModule {

  /**
   * Specifies additional arguments for the Android Lint tool.
   * Allows for complete customization of the lint command.
   */
  def androidLintArgs: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Runs the Android Lint tool to generate a report on code quality issues.
   *
   * This method utilizes Android Lint, a tool provided by the Android SDK,
   * to analyze the source code for potential bugs, performance issues, and
   * best practices compliance. It generates a report in the specified format.
   *
   * The lint tool requires the Android SDK's command-line tools to be installed.
   * The report is saved in the task's destination directory with the appropriate extension.
   *
   * For more details on the Android Lint tool, refer to:
   * [[https://developer.android.com/studio/write/lint]]
   */
  def androidLintRun: T[PathRef] = Task {
    os.call(
      Seq(
        androidSdkModule().cmdlineToolsPath().path.toString + "/lint",
        millSourcePath.toString
      ) ++ androidLintArgs()
    )

    PathRef(Task.dest)
  }

}
