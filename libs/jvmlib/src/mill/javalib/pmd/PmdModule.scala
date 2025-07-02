package mill.javalib.pmd

import mill.*
import mill.api.{Discover, ExternalModule, TaskCtx}
import mill.jvmlib.api.Versions
import mill.scalalib.scalafmt.ScalafmtModule.sources
import mill.scalalib.{CoursierModule, Dep, DepSyntax, OfflineSupportModule}
import mill.util.Jvm

/**
 * Checks Java source files with PMD static code analyzer [[https://pmd.github.io/]].
 */
trait PmdModule extends CoursierModule, OfflineSupportModule {

  /**
   * Runs PMD and returns the number of violations found (exit code).
   *
   * @note [[sources]] are processed when no [[PmdArgs.sources]] are specified.
   */
  def pmd(@mainargs.arg pmdArgs: PmdArgs): Command[(exitCode: Int, outputPath: PathRef)] =
    Task.Command {
      val (outputPath, exitCode) = pmd0(pmdArgs.format, pmdArgs.sources)()
      pmdHandleExitCode(
        pmdArgs.stdout,
        pmdArgs.noFailOnViolation,
        exitCode,
        outputPath,
        pmdArgs.format
      )
      (exitCode, outputPath)
    }

  protected def pmd0(format: String, leftover: mainargs.Leftover[String]) =
    Task.Anon {
      val output = Task.dest / s"pmd-output.$format"
      os.makeDir.all(output / os.up)
      val baseArgs = Seq(
        "-d",
        if (leftover.value.nonEmpty) leftover.value.mkString(",")
        else sources().map(_.path.toString()).mkString(","),
        "-R",
        pmdRulesets().map(_.path.toString).mkString(","),
        "-f",
        format,
        "-r",
        output.toString
      )

      val args =
        if (isPmd6OrOlder(this.pmdVersion())) pmdOptions() ++ baseArgs
        else pmdOptions() ++ (Seq("check") ++ baseArgs)
      val mainCls =
        if (isPmd6OrOlder(this.pmdVersion())) "net.sourceforge.pmd.PMD"
        else "net.sourceforge.pmd.cli.PmdCli"
      val jvmArgs = pmdLanguage().map(lang => s"-Duser.language=$lang").toSeq

      Task.log.info("Running PMD...")
      Task.log.debug(s"with $args")
      Task.log.info(s"Writing PMD output to: $output...")

      val exitCode = Jvm.callProcess(
        mainCls,
        classPath = pmdClasspath().map(_.path).toVector,
        mainArgs = args,
        cwd = moduleDir,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = false,
        jvmArgs = jvmArgs
      ).exitCode

      (PathRef(output), exitCode)
    }

  private def pmdHandleExitCode(
      stdout: Boolean,
      noFailOnViolation: Boolean,
      exitCode: Int,
      output: PathRef,
      format: String
  )(implicit ctx: TaskCtx): Int = {
    exitCode match
      case 0 => Task.log.info("No violations found and no recoverable error occurred.")
      case 1 => Task.log.error("PMD finished with an exception.")
      case 2 => Task.log.error("PMD command-line parameters are invalid or missing.")
      case 4 =>
        reportViolations(noFailOnViolation, countViolations(output, format, stdout))
      case 5 =>
        reportViolations(noFailOnViolation, countViolations(output, format, stdout))
        throw new RuntimeException("At least one recoverable PMD error has occurred.")
      case x => Task.log.error(s"Unsupported PMD exit code: $x")
    exitCode
  }

  private def countViolations(
      output: PathRef,
      format: String,
      stdout: Boolean
  )(implicit ctx: TaskCtx): Option[Int] = {
    var violationCount: Option[Int] = None
    val lines = os.read.lines(output.path)
    if (lines.nonEmpty) {
      if (stdout) {
        Task.log.info("PMD violations:")
        lines.foreach(line => Task.log.info(line))
      }
      // For "text" format: each line is a violation
      if (format == "text") {
        violationCount = Some(lines.size)
      }
      // For "xml" format: count <violation ...> tags
      else if (format == "xml") {
        violationCount = Some(lines.count(_.trim.startsWith("<violation")))
      }
      // For "html" format: count lines with <tr but skip the header row
      else if (format == "html") {
        violationCount =
          Some(lines.count(line => line.trim.startsWith("<tr") && !line.contains("<th")))
      }
    } else {
      violationCount = Some(0)
    }
    violationCount
  }

  private def reportViolations(
      noFailOnViolation: Boolean,
      violationCount: Option[Int]
  )(implicit ctx: TaskCtx): Unit = {
    if (noFailOnViolation)
      Task.log.error(s"PMD found ${violationCount.getOrElse("some")} violation(s)")
    else
      throw new RuntimeException(s"PMD found ${violationCount.getOrElse("some")} violation(s)")
  }

  /**
   * Classpath for running PMD.
   */
  def pmdClasspath: T[Seq[PathRef]] = Task {
    val version = pmdVersion()
    defaultResolver().classpath(Seq(mvn"net.sourceforge.pmd:pmd-dist:$version"))
  }

  /** PMD rulesets files. Defaults to `pmd-ruleset.xml`. */
  def pmdRulesets: Sources = Task.Sources(moduleDir / "pmd-ruleset.xml")

  /** Additional arguments for PMD. */
  def pmdOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /** User language of the JVM running PMD. */
  def pmdLanguage: T[Option[String]] = Task.Input {
    sys.props.get("user.language")
  }

  /** Helper to check if the version is <= 6. False by default. */
  private def isPmd6OrOlder(version: String): Boolean = {
    version
      .split(":").lastOption
      .flatMap(_.takeWhile(_ != '.').toIntOption)
      .exists(_ < 7)
  }

  /** PMD version. */
  def pmdVersion: T[String] = Task { Versions.pmdVersion }
}

/**
 * External module for PMD integration.
 * Allows usage via `import mill.javalib.pmd.PmdModule` in build.sc.
 */
object PmdModule extends ExternalModule, PmdModule {
  lazy val millDiscover: Discover = Discover[this.type]
  def defaultCommandName() = "pmd"
}
