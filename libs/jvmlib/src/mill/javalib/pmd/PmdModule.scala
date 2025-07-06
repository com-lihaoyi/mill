package mill.javalib
package pmd

import mainargs.Flag
import mill.api.*
import mill.constants.OutFiles.out
import mill.jvmlib.api.Versions.{pmdCli, pmdDist, pmdJava}
import mill.scalalib.{JavaHomeModule, OfflineSupportModule}
import mill.util.Jvm

/**
 * Adds support for analyzing sources with the [[pmd]] command.
 * @see [[https://docs.pmd-code.org/latest/index.html PMD]]
 */
@mill.api.experimental
trait PmdModule extends CoursierModule, OfflineSupportModule {

  /**
   * Configuration files containing rules for analysis. Defaults to XML files in [[moduleDir]]
   * (or ''workspace'') with name starting with ''ruleset''.
   * @see [[https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html Rulesets]]
   */
  def pmdRulesets: Task[Seq[PathRef]] = {
    def rulesetsIn(root: os.Path) = {
      val rulesets = os.list.stream(root)
        .filter: path =>
          os.isFile(path) && {
            val name = path.last
            name.startsWith("ruleset") && name.endsWith(".xml")
          }
        .toSeq
      Option.when(rulesets.nonEmpty)(rulesets)
    }
    Task.Sources(rulesetsIn(moduleDir)
      .orElse(rulesetsIn(Task.ctx().workspace))
      .getOrElse(Task.fail("failed to auto-detect rulesets"))*)
  }

  /**
   * Files (or folders containing the files) to analyze. Defaults to
   *  - (non-hidden) folders in workspace, for a [[BaseModule root module]]
   *  - `sources`, for a [[JavaModule]]
   *  - folders under [[moduleDir]] with name starting with ''src'', otherwise
   * @note Values in [[pmdExcludes]] supersede values in this list.
   */
  def pmdIncludes: Task[Seq[PathRef]] = this match
    case _: BaseModule => Task.Sources(os.list.stream(moduleDir)
        .filter: path =>
          os.isDir(path) && {
            val name = path.last
            !(name.startsWith(".") || name == out)
          }
        .toSeq*)
    case self: JavaModule => Task(self.sources())
    case _ => Task.Sources(os.list.stream(moduleDir)
        .filter: path =>
          os.isDir(path) && path.relativeTo(moduleDir).segments.head.startsWith("src")
        .toSeq*)

  /**
   * Files or folders to exclude from analysis.
   * @note This is intended for use in conjunction with [[pmdIncludes]].
   */
  def pmdExcludes: Task[Seq[PathRef]] = Task(Seq.empty[PathRef])

  /**
   * The specific language and version to use when parsing source code. Defaults to line delimited
   * values in ''.pmd-use-version''.
   * @see `--use-version` in [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#options options]]
   */
  def pmdUseVersion: Task[Map[String, String]] = Task.Input(BuildCtx.withFilesystemCheckerDisabled:
    val file = moduleDir / ".pmd-use-version"
    if os.exists(file) then
      os.read.lines.stream(file)
        .grouped(2)
        .map(pair => (pair.head, pair.last))
        .toSeq
        .toMap
    else Map())

  /**
   * Dependencies required to run [[pmd]]. Defaults to ''net.sourceforge.pmd'' dependency
   *  - ''pmd-dist'', for a [[BaseModule root module]]
   *  - ''pmd-cli'' and ''pmd-java'', for a [[JavaModule]]
   *  - ''pmd-cli'', otherwise
   * @note [[https://docs.pmd-code.org/latest/pmd_userdocs_3rdpartyrulesets.html 3rd party rules]]
   *       can be used after adding the dependencies here.
   */
  def pmdMvnDeps: Task[Seq[Dep]] = this match
    case _: BaseModule => Task(Seq(mvn"$pmdDist"))
    case _: JavaModule => Task(Seq(mvn"$pmdCli", mvn"$pmdJava"))
    case _ => Task(Seq(mvn"$pmdCli"))

  /**
   * Classpath containing [[pmdMvnDeps]].
   */
  def pmdClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(pmdMvnDeps())
  }

  /**
   * Java runtime options for running the analyzer. Defaults to
   *  - `forkArgs`, for a [[RunModule]]
   *  - line delimited values in ''.pmd_java_opts'', if the file exists
   * @see [[https://docs.pmd-code.org/latest/pmd_languages_java.html#using-java-preview-features Using java preview features]]
   */
  def pmdJavaOptions: Task[Seq[String]] = this match
    case self: RunModule => Task(self.forkArgs())
    case _ => Task.Input(BuildCtx.withFilesystemCheckerDisabled:
        val file = moduleDir / ".pmd_java_opts"
        if os.exists(file) then os.read.lines(file) else Seq())

  /**
   * Analyzes [[pmdIncludes files]] with [[pmdRulesets rulesets]] and fails on violations.
   * @param options Additional [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#options CLI options]].
   * @note The `--cache` option is reserved for internal use.
   */
  def pmd(options: String*): Task.Command[Unit] = {
    // running PMD with the module's Java home should obviate the need for
    // https://docs.pmd-code.org/latest/pmd_languages_java.html#providing-the-auxiliary-classpath
    val javaHomeTask = this match
      case self: JavaHomeModule => Task.Anon(self.javaHome().map(_.path))
      case _ => Task.Anon(Option.empty[os.Path])
    Task.Command {
      val cliArgs =
        Seq("check") // sub-command
          ++ Seq("--cache", (Task.dest / "cache").toString) // for incremental analysis
          ++ pmdUseVersion().iterator.flatMap((lang, ver) => Seq("--use-version", s"$lang-$ver"))
          ++ pmdRulesets().iterator.flatMap(ref => Seq("-R", ref.path.toString))
          ++ pmdExcludes().iterator.flatMap(ref => Seq("--exclude", ref.path.toString))
          ++ pmdIncludes().iterator.flatMap(ref => Seq("-d", ref.path.toString))
          ++ options
      val exitCode = Jvm.callProcess(
        mainClass = "net.sourceforge.pmd.cli.PmdCli",
        mainArgs = cliArgs,
        javaHome = javaHomeTask(),
        jvmArgs = pmdJavaOptions(),
        classPath = pmdClasspath().map(_.path),
        check = false,
        stdout = os.Inherit // log violations
      ).exitCode
      exitCode match
        case 0 => Task.log.info("no violation found")
        case 2 => Task.fail("invalid/missing options")
        case 4 => Task.fail("violation(s) found")
        case 5 => Task.fail("recoverable error(s) occurred")
        case x => Task.fail(s"exit($x)")
    }
  }

  override def prepareOffline(all: Flag) = Task.Command {
    (super.prepareOffline(all)() ++ pmdClasspath()).distinct
  }
}
@mill.api.experimental
object PmdModule extends ExternalModule, TaskModule, PmdModule {
  lazy val millDiscover = Discover[this.type]
  def defaultTask() = "pmd"
}
