package mill.javalib
package pmd

import mainargs.Flag
import mill.constants.OutFiles.out
import mill.define.*
import mill.scalalib.{JavaHomeModule, OfflineSupportModule}
import mill.util.Jvm

/**
 * Adds support for analyzing sources with [[https://docs.pmd-code.org/latest/index.html PMD]]
 * (Programming Mistake Detector).
 */
@mill.api.experimental
trait PmdModule extends CoursierModule, OfflineSupportModule {

  /**
   * [[https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html Rulesets]] for analysis.
   * Defaults to XML files, with name prefix ''ruleset'', in one of [[moduleDir]] or ''workspace''.
   */
  def pmdRulesets: Task[Seq[PathRef]] = {
    def isRulesetFile(name: String) = name.startsWith("ruleset") && name.endsWith(".xml")
    def rulesetsIn(root: os.Path) =
      val rulesets = os.list.stream(root)
        .filter(os.isFile)
        .filter(file => isRulesetFile(file.last))
        .toSeq
      Option.when(rulesets.nonEmpty)(rulesets)

    Task.Sources(rulesetsIn(moduleDir)
      .orElse(rulesetsIn(Task.ctx().workspace))
      .getOrElse({
        Task.log.warn("no rulesets found, override pmdRulesets or specify files with -R option")
        Seq()
      })*)
  }

  /**
   * The specific [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#supported-languages language]]
   * and version to use when parsing source code for a given language.
   */
  def pmdUseVersion: Task[Map[String, String]] = Task(Map.empty[String, String])

  /**
   * Files/folders to analyze. Defaults to
   *  - `sources`, for a [[JavaModule]]
   *  - folders under [[moduleDir]] with name prefix ''src'', otherwise
   */
  def pmdInputs: Task[Seq[PathRef]] = this match
    case self: JavaModule => Task(self.sources())
    case _ => Task.Sources(os.list.stream(moduleDir)
        .filter(os.isDir)
        .filter(_.relativeTo(moduleDir).segments.head.startsWith("src"))
        .toSeq*)

  /**
   * Files/folders, in [[pmdInputs]], to not analyze.
   */
  def pmdExcludes: Task[Seq[PathRef]] = Task(Seq.empty[PathRef])

  /**
   * Classpath containing the PMD distribution dependency.
   * @note Custom rulesets defined in a separate module/artifact can be used in a ruleset XML file
   *       by appending the `transitiveCompileClasspath`/dependency to this classpath.
   */
  def pmdClasspath: Task[Seq[PathRef]] = Task {
    /*
      A task worker should not be used to cache this classpath.
      - The CLI main class calls `System.exit` with the result code.
        A `mainWithoutExit` method is present but this is package protected.
      - Java preview features require passing a runtime option.
        See https://docs.pmd-code.org/latest/pmd_languages_java.html#using-java-preview-features.
     */
    defaultResolver().classpath(Seq(mvn"${mill.scalalib.api.Versions.pmdDist}"))
  }

  /**
   * Java runtime options, like `--enable-preview`, for running the analyzer. Defaults to
   *  - `forkArgs`, for a [[RunModule]]
   */
  def pmdJavaOptions: Task[Seq[String]] = this match
    case self: RunModule => Task(self.forkArgs())
    case _ => Task(Seq.empty[String])

  /**
   * Analyzes [[pmdInputs]]. The task fails if
   *  - violations are found
   *  - recoverable errors occur
   *
   * Additional [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#options options]]
   * can be passed, except
   *  - `--cache`
   *  - `-z`
   *  - `-r`, if [[pmdReportFile]] is defined
   * @note The analyzer is run with [[JavaHomeModule.javaHome]], if defined.
   *       This should eliminate the need for specifying the
   *       [[https://docs.pmd-code.org/latest/pmd_languages_java.html#providing-the-auxiliary-classpath --aux-classpath]] option.
   */
  def pmd(options: String*): Task.Command[Unit] = {
    val javaHomeTask = this match
      case self: JavaHomeModule => Task.Anon(self.javaHome().map(_.path))
      case _ => Task.Anon(Option.empty[os.Path])
    Task.Command {
      val cliArgs =
        Seq("check") // sub-command
          ++ options
          // for incremental analysis, must be absolute path
          ++ Seq("--cache", (Task.dest / "cache").toString)
          // relativize paths in output report
          ++ Seq("-z", Task.ctx().workspace.toString)
          ++ pmdUseVersion().iterator.flatMap((lng, ver) => Seq("--use-version", s"$lng-$ver"))
          ++ pmdRulesets().iterator.flatMap(ref => Seq("-R", ref.path.toString))
          ++ pmdExcludes().iterator.flatMap(ref => Seq("--exclude", ref.path.toString))
          ++ pmdInputs().iterator.flatMap(ref => Seq("-d", ref.path.toString))
      val exitCode = Jvm.callProcess(
        mainClass = "net.sourceforge.pmd.cli.PmdCli",
        mainArgs = cliArgs,
        javaHome = javaHomeTask(),
        jvmArgs = pmdJavaOptions(),
        classPath = pmdClasspath().map(_.path),
        cwd = moduleDir, // support relative paths in CLI args
        check = false,
        stdout = os.Inherit // log file violations
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

  def defaultCommandName() = "pmd"

  /**
   * Defaults to language and version values, each in a separate line, in ''.pmd-use-version''.
   */
  override def pmdUseVersion = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      val file = moduleDir / ".pmd-use-version"
      if os.exists(file) then
        os.read.lines.stream(file)
          .grouped(2)
          .map(pair => (pair.head, pair.last))
          .toSeq
          .toMap
      else Map()
    }
  }

  /**
   * Defaults to folders in workspace, except hidden folders and [[out]] folder.
   */
  override def pmdInputs = Task.Sources(os.list.stream(moduleDir)
    .filter(path =>
      os.isDir(path) && {
        val name = path.last
        !(name.startsWith(".") || name == out)
      }
    )
    .toSeq*)

  /**
   * Defaults to runtime option values, each in a separate line, in ''.pmd_java_opts''.
   */
  override def pmdJavaOptions = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      val file = moduleDir / ".pmd_java_opts"
      if os.exists(file) then os.read.lines(file) else Seq()
    }
  }
}
