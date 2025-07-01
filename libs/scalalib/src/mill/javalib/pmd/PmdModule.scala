package mill.javalib
package pmd

import mainargs.Flag
import mill.constants.OutFiles.out
import mill.define.*
import mill.scalalib.{JavaHomeModule, OfflineSupportModule}
import mill.util.Jvm

/**
 * Adds support for analyzing sources with [[https://docs.pmd-code.org/latest/index.html PMD]].
 */
@mill.api.experimental
trait PmdModule extends CoursierModule, OfflineSupportModule {

  /**
   * [[https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html Rulesets]] for analysis.
   * Defaults to XML files with name prefix ''ruleset'' in one of [[moduleDir]] or ''workspace''.
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
   *  - (non-hidden) folders in workspace, for a [[BaseModule]]
   *  - `sources`, for a [[JavaModule]]
   *  - folders under [[moduleDir]] with name prefix ''src'', otherwise
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
   * @note This is intended to be used in conjunction with [[pmdIncludes]].
   */
  def pmdExcludes: Task[Seq[PathRef]] = Task(Seq.empty[PathRef])

  /**
   * The specific [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#supported-languages language]]
   * and version to use when parsing source code for a given language. Defaults to line delimited
   * values in ''.pmd-use-version''.
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
   * Classpath containing the PMD distribution dependency.
   *
   * Custom rulesets defined in a separate module/artifact can be used after adding the
   * `transitiveCompileClasspath`/dependency to this classpath.
   */
  def pmdClasspath: Task[Seq[PathRef]] = Task {
    /*
      This classpath cannot be cached in a task worker.
      - The CLI main class calls System.exit.
      - We need the ability to pass Java runtime options like --enable-preview.
     */
    defaultResolver().classpath(Seq(mvn"${mill.scalalib.api.Versions.pmdDist}"))
  }

  /**
   * Java runtime options, like `--enable-preview`, for running the analyzer. Defaults to
   *  - `forkArgs`, for a [[RunModule]]
   *  - line delimited values in ''.pmd_java_opts'', if the file exists
   */
  def pmdJavaOptions: Task[Seq[String]] = this match
    case self: RunModule => Task(self.forkArgs())
    case _ => Task.Input(BuildCtx.withFilesystemCheckerDisabled:
        val file = moduleDir / ".pmd_java_opts"
        if os.exists(file) then os.read.lines(file) else Seq())

  /**
   * Analyzes [[pmdIncludes files]] and fails on [[pmdRulesets ruleset]] violations. Extra
   * [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#options options]],
   * such as `-f textcolor`, can be passed as arguments.
   * @note The `--cache` option is reserved for internal use.
   */
  def pmd(options: String*): Task.Command[Unit] = {
    /*
    Running with the module's Java home should obviate the need for the --aux-classpath option.
    See https://docs.pmd-code.org/latest/pmd_languages_java.html#providing-the-auxiliary-classpath.
     */
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
        case 0 => Task.log.info("no violations found")
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
