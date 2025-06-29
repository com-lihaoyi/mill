package mill.javalib.pmd

import mill.scalalib.{DepSyntax, JavaModule}
import mill.util.Jvm
import mill.*

/**
 * Checks Java source files with PMD static code analyzer [[https://pmd.github.io/]].
 */
trait PmdModule extends JavaModule {

  /**
   * Runs PMD and returns the number of violations found (exit code).
   *
   * @note [[sources]] are processed when no [[PmdArgs.sources]] are specified.
   */
  def pmd(@mainargs.arg pmdArgs: PmdArgs): Command[Int] = Task.Command {
    val (output, exitCode) = pmd0(pmdArgs.stdout, pmdArgs.format, pmdArgs.sources)()
    pmdHandleErrors(pmdArgs.stdout, pmdArgs.check, exitCode, output)
  }

  protected def pmd0(stdout: Boolean, format: String, leftover: mainargs.Leftover[String]) = Task.Anon {
    val output = Task.dest / s"pmd-output.$format"
    val args = pmdOptions() ++
      Seq(
        "-d",
        (if (leftover.value.nonEmpty) leftover.value.mkString(",")
        else sources().map(_.path.toString()).mkString(",")),
        "-R",
        pmdRulesets().map(_.path.toString).mkString(","),
        "-f",
        format
      ) ++
      (if (stdout) Seq.empty else Seq("-r", output.toString))
    val jvmArgs = pmdLanguage().map(lang => s"-Duser.language=$lang").toSeq

    Task.log.info("running pmd ...")
    Task.log.debug(s"with $args")

    val exitCode = Jvm.callProcess(
      mainClass = "net.sourceforge.pmd.PMD",
      classPath = pmdClasspath().map(_.path).toVector,
      mainArgs = args,
      cwd = moduleDir,
      stdin = os.Inherit,
      stdout = os.Inherit,
      check = false,
      jvmArgs = jvmArgs
    ).exitCode

    (output, exitCode)
  }

  protected def pmdHandleErrors(
                                 stdout: Boolean,
                                 check: Boolean,
                                 exitCode: Int,
                                 output: os.Path
                               )(implicit ctx: mill.define.TaskCtx): Int = {

    val reported = os.exists(output)
    if (reported) {
      Task.log.info(s"pmd output report at $output")
    }

    if (exitCode == 0) {} // do nothing
    else if (exitCode < 0 || !(reported || stdout)) {
      Task.log.error(
        s"pmd exit($exitCode); please check command arguments, plugin settings or try with another version"
      )
      throw new UnsupportedOperationException(s"pmd exit($exitCode)")
    } else if (check) {
      throw new RuntimeException(s"pmd found $exitCode violation(s)")
    } else {
      Task.log.error(s"pmd found $exitCode violation(s)")
    }

    exitCode
  }

  /** Classpath for running PMD. */
  def pmdClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"net.sourceforge.pmd:pmd-dist:${pmdVersion()}")
    )
  }

  /** PMD rulesets files. Defaults to `pmd-ruleset.xml`. */
  def pmdRulesets: Sources = Task.Sources(moduleDir / "pmd-ruleset.xml")

  /** PMD output format (`text`, `xml`, `html`, etc). Defaults to `text`. */
  def pmdFormat: T[String] = Task { "text" }

  /** Additional arguments for PMD. */
  def pmdOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /** User language of the JVM running PMD. */
  def pmdLanguage: T[Option[String]] = Task.Input {
    sys.props.get("user.language")
  }

  /** PMD output report. */
  def pmdOutput: T[PathRef] = Task { PathRef(Task.dest / s"pmd-output.${pmdFormat()}") }

  /** PMD version. */
  def pmdVersion: T[String]
}
