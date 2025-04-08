package mill.javalib.checkstyle

import mill._
import mill.api.{PathRef}
import mill.scalalib.{DepSyntax, JavaModule}
import mill.util.Jvm

/**
 * Performs quality checks on Java source files using [[https://checkstyle.org/ Checkstyle]].
 */
trait CheckstyleModule extends JavaModule {

  /**
   * Runs [[https://checkstyle.org/cmdline.html#Command_line_usage Checkstyle]] and returns one of
   *  - number of violations found
   *  - program exit code
   *
   * @note [[sources]] are processed when no [[CheckstyleArgs.sources]] are specified.
   */
  def checkstyle(@mainargs.arg checkstyleArgs: CheckstyleArgs): Command[Int] = Task.Command {
    val (output, exitCode) = checkstyle0(checkstyleArgs.stdout, checkstyleArgs.sources)()

    checkstyleHandleErrors(checkstyleArgs.stdout, checkstyleArgs.check, exitCode, output)
  }

  protected def checkstyle0(stdout: Boolean, leftover: mainargs.Leftover[String]) = Task.Anon {

    val output = checkstyleOutput().path
    val args = checkstyleOptions() ++
      Seq("-c", checkstyleConfig().path.toString()) ++
      Seq("-f", checkstyleFormat()) ++
      (if (stdout) Seq.empty else Seq("-o", output.toString())) ++
      (if (leftover.value.nonEmpty) leftover.value else sources().map(_.path.toString()))
    val jvmArgs = checkstyleLanguage()
      .map(lang => s"-Duser.language=$lang")
      .toSeq

    Task.log.info("running checkstyle ...")
    Task.log.debug(s"with $args")

    val exitCode = Jvm.callProcess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = checkstyleClasspath().map(_.path).toVector,
      mainArgs = args,
      cwd = moduleDir, // allow passing relative paths for sources like src/a/b
      stdin = os.Inherit,
      stdout = os.Inherit,
      check = false,
      jvmArgs = jvmArgs
    ).exitCode

    (output, exitCode)
  }

  protected def checkstyleHandleErrors(
      stdout: Boolean,
      check: Boolean,
      exitCode: Int,
      output: os.Path
  )(implicit ctx: mill.api.Ctx): Int = {

    val reported = os.exists(output)
    if (reported) {
      Task.log.info(s"checkstyle output report at $output")
    }

    if (exitCode == 0) {} // do nothing
    else if (exitCode < 0 || !(reported || stdout)) {
      Task.log.error(
        s"checkstyle exit($exitCode); please check command arguments, plugin settings or try with another version"
      )
      throw new UnsupportedOperationException(s"checkstyle exit($exitCode)")
    } else if (check) {
      throw new RuntimeException(s"checkstyle found $exitCode violation(s)")
    } else {
      Task.log.error(s"checkstyle found $exitCode violation(s)")
    }

    exitCode
  }

  /**
   * Classpath for running Checkstyle.
   */
  def checkstyleClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}")
    )
  }

  /**
   * Checkstyle configuration file. Defaults to `checkstyle-config.xml`.
   */
  def checkstyleConfig: T[PathRef] = Task {
    PathRef(Task.workspace / "checkstyle-config.xml")
  }

  /**
   * Checkstyle output format (` plain | sarif | xml `). Defaults to `plain`.
   */
  def checkstyleFormat: T[String] = Task {
    "plain"
  }

  /**
   * Additional arguments for Checkstyle.
   */
  def checkstyleOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /**
   * User language of the JVM running checkstyle.
   *
   * This can affect the messages in the checkstyle output file.
   */
  def checkstyleLanguage: T[Option[String]] = Task.Input {
    sys.props.get("user.language")
  }

  /**
   * Checkstyle output report.
   */
  def checkstyleOutput: T[PathRef] = Task {
    PathRef(Task.dest / s"checkstyle-output.${checkstyleFormat()}")
  }

  /**
   * Checkstyle version.
   */
  def checkstyleVersion: T[String] = Task {
    "10.18.1"
  }
}
