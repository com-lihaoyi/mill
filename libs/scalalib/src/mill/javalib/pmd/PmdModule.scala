package mill.javalib.pmd

import mill.*
import mill.define.{Discover, ExternalModule}
import mill.scalalib.api.Versions
import mill.scalalib.scalafmt.ScalafmtModule.sources
import mill.scalalib.{CoursierModule, DepSyntax, OfflineSupportModule}
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
  def pmd(@mainargs.arg pmdArgs: PmdArgs): Command[Int] = Task.Command {
    val (output, exitCode) = pmd0(pmdArgs.stdout, pmdArgs.format, pmdArgs.sources)()
    pmdHandleErrors(pmdArgs.stdout, pmdArgs.check, exitCode, output)
  }

  protected def pmd0(stdout: Boolean, format: String, leftover: mainargs.Leftover[String]) =
    Task.Anon {
      val output = Task.dest / s"pmd-output.$format"
      os.makeDir.all(output / os.up)
      val baseArgs = Seq(
        "-d",
        (if (leftover.value.nonEmpty) leftover.value.mkString(",")
         else sources().map(_.path.toString()).mkString(",")),
        "-R",
        pmdRulesets().map(_.path.toString).mkString(","),
        "-f",
        format
      ) ++ (if (stdout) Seq.empty else Seq("-r", output.toString))

      val args =
        if (isPmd6OrOlder(this.pmdVersion())) pmdOptions() ++ baseArgs
        else pmdOptions() ++ (Seq("check") ++ baseArgs)
      val mainCls =
        if (isPmd6OrOlder(this.pmdVersion())) "net.sourceforge.pmd.PMD"
        else "net.sourceforge.pmd.cli.PmdCli"
      val jvmArgs = pmdLanguage().map(lang => s"-Duser.language=$lang").toSeq

      Task.log.info("running pmd ...")
      Task.log.debug(s"with $args")

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

  /**
   * Classpath for running PMD.
   * If pmdVersion is set as a plain version (like "7.15.0"), it constructs the Maven dependency.
   * If it returns a Maven coordinate string (like "net.sourceforge.pmd:pmd-dist:7.15.0"), it uses that directly.
   * If nothing is set, it falls back to the default from mill.scalalib.api.Versions.pmdDist.
   */
  def pmdClasspath: T[Seq[PathRef]] = Task {
    val versionOrDep = pmdVersion().trim
    val dep: mill.scalalib.Dep =
      if (versionOrDep.matches("""^\d+(\.\d+)*$""")) {
        mvn"net.sourceforge.pmd:pmd-dist:$versionOrDep"
      } else if (versionOrDep.startsWith("net.sourceforge.pmd:pmd-dist:")) {
        mvn"${versionOrDep}"
      } else {
        // If it's not a version, try to cast to Dep (e.g. from Deps.RuntimeDeps.pmdDist)
        mill.scalalib.api.Versions.pmdDist.asInstanceOf[mill.scalalib.Dep]
      }
    defaultResolver().classpath(Seq(dep))
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

  /** Helper to check if the version is <= 6. False by default. */
  private def isPmd6OrOlder(version: String): Boolean = {
    version
      .split(":").lastOption
      .flatMap(_.takeWhile(_ != '.').toIntOption)
      .exists(_ < 7)
  }

  /** PMD version. */
  def pmdVersion: T[String] = Task { Versions.pmdDist }
}

/**
 * External module for PMD integration.
 * Allows usage via `import mill.javalib.pmd.PmdModule` in build.sc.
 */
object PmdModule extends ExternalModule, PmdModule {
  lazy val millDiscover = Discover[this.type]

  def defaultCommandName() = "pmd"
}
