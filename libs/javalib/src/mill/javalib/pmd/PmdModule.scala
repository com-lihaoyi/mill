package mill.javalib.pmd

import mill.*
import mill.api.{Discover, ExternalModule, TaskCtx}
import mill.api.daemon.experimental
import mill.javalib.api.Versions
import mill.javalib.{CoursierModule, Dep, DepSyntax, OfflineSupportModule}
import mill.util.{Jvm, Version}

/**
 * Checks Java source files with PMD static code analyzer [[https://pmd.github.io/]].
 */
@experimental
trait PmdModule extends CoursierModule, OfflineSupportModule {

  /**
   * Runs PMD.
   *
   * @note [[sources]] are processed when no [[PmdArgs.sources]] are specified.
   */
  def pmd(@mainargs.arg pmdArgs: PmdArgs): Command[(exitCode: Int, outputPath: PathRef)] =
    Task.Command {
      val res = pmd0(pmdArgs.format, pmdArgs.sources)()
      pmdHandleExitCode(
        pmdArgs.stdout,
        pmdArgs.failOnViolation,
        res.exitCode,
        res.outputPath,
        pmdArgs.format
      )
      (res.exitCode, res.outputPath)
    }

  protected def pmd0(
      format: String,
      leftover: mainargs.Leftover[String]
  ): Task[(outputPath: PathRef, exitCode: Int)] =
    Task.Anon {
      val output = Task.dest / s"pmd-output.$format"
      os.makeDir.all(output / os.up)
      val baseArgs = Seq(
        "-d",
        if (leftover.value.nonEmpty) leftover.value.mkString(",")
        else "",
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

      Task.log.info("Running PMD ...")
      Task.log.debug(s"with $args")
      Task.log.info(s"Writing PMD output to $output ...")

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

      (outputPath = PathRef(output), exitCode = exitCode)
    }

  private def pmdHandleExitCode(
      stdout: Boolean,
      failOnViolation: Boolean,
      exitCode: Int,
      output: PathRef,
      format: String
  )(implicit ctx: TaskCtx): Int = {
    exitCode match
      case 0 => Task.log.info("No violations found and no recoverable error occurred.")
      case 1 => Task.log.error("PMD finished with an exception.")
      case 2 => Task.log.error("PMD command-line parameters are invalid or missing.")
      case 4 =>
        reportViolations(failOnViolation, countViolations(output, format, stdout))
      case 5 =>
        reportViolations(failOnViolation, countViolations(output, format, stdout))
        Task.fail("At least one recoverable PMD error has occurred.")
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
      failOnViolation: Boolean,
      violationCount: Option[Int]
  )(implicit ctx: TaskCtx): Unit = {
    val msg = s"PMD found ${violationCount.getOrElse("some")} violation(s)"
    if (failOnViolation) Task.fail(msg)
    else Task.log.error(msg)
  }

  /**
   * Classpath for running PMD.
   */
  def pmdClasspath: T[Seq[PathRef]] = Task {
    val version = pmdVersion()
    defaultResolver().classpath(Seq(mvn"net.sourceforge.pmd:pmd-dist:$version"))
  }

  /** PMD rulesets files. Defaults to `pmd-ruleset.xml`. */
  def pmdRulesets: T[Seq[PathRef]] = Task.Sources(moduleDir / "pmd-ruleset.xml")

  /** Additional arguments for PMD. */
  def pmdOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /** User language of the JVM running PMD. */
  def pmdLanguage: T[Option[String]] = Task.Input {
    sys.props.get("user.language")
  }

  /** Helper to check if the version is <= 6. False by default. */
  private def isPmd6OrOlder(version: String): Boolean =
    !Version.isAtLeast(version, "7")(using Version.IgnoreQualifierOrdering)

  /** PMD version. */
  def pmdVersion: T[String] = Task { Versions.pmdVersion }
}

/**
 * External module for PMD integration.
 *
 * Allows usage via `import mill.javalib.pmd/` in build.mill.
 */
@experimental
object PmdModule extends ExternalModule, PmdModule, DefaultTaskModule {
  lazy val millDiscover: Discover = Discover[this.type]
  override def defaultTask() = "pmd"
}
