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
   * Paths to [[https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html rulesets]]
   * defining analysis rules to be executed. Defaults to XML files, starting with name ''ruleset'',
   * under
   *  - [[moduleDir]], if rulesets exist
   *  - ''workspace'', otherwise
   * @note Path values can be specified relative to [[moduleDir]].
   */
  def pmdRulesets: Task[Seq[String]] = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      def isRulesetFile(name: String) = name.startsWith("ruleset") && name.endsWith(".xml")
      def rulesetsIn(root: os.Path) =
        val rulesets = os.list.stream(root)
          .collect:
            case path if os.isFile(path) && isRulesetFile(path.last) => path.toString()
          .toSeq
        Option.when(rulesets.nonEmpty)(rulesets)

      rulesetsIn(moduleDir)
        .orElse(rulesetsIn(Task.ctx().workspace))
        .getOrElse(Task.fail("rulesets not found"))
    }
  }

  /**
   * Output [[https://docs.pmd-code.org/latest/pmd_userdocs_report_formats.html format]] of the
   * analysis report. Defaults to ''text''.
   */
  def pmdFormat: Task[String] = Task("text")

  /**
   * The specific [[https://docs.pmd-code.org/latest/pmd_userdocs_cli_reference.html#supported-languages language]]
   * version PMD should use when parsing source code for a given language.
   */
  def pmdUseVersion: Task[Map[String, String]] = Task(Map.empty[String, String])

  /**
   * Path to a file to which report output is written. If not specified, the report is rendered to
   * standard output.
   */
  def pmdReportFile: Task[Option[PathRef]] = Task(Option.empty[PathRef])

  /**
   * Paths to files/folders to analyze. Defaults to
   *  - `sources`, for a [[JavaModule]]
   *  - folders under [[moduleDir]] with name starting with ''src'', otherwise
   * @note Path values can be specified relative to [[moduleDir]].
   */
  def pmdInputs: Task[Seq[String]] = this match
    case self: JavaModule => Task(self.sources().map(_.path.toString))
    case _ => Task {
        BuildCtx.withFilesystemCheckerDisabled {
          os.list.stream(moduleDir)
            .filter(os.isDir)
            .map(_.relativeTo(moduleDir).toString())
            .filter(_.startsWith("src"))
            .toSeq
        }
      }

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
    defaultResolver().classpath(Seq(mvn"net.sourceforge.pmd:pmd-dist:${pmdVersion()}"))
  }

  /**
   * PMD version to use. Defaults to ''7.15.0''.
   */
  def pmdVersion: Task[String] = Task { "7.15.0" }

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
   * can be passed, excluding
   *  - `--cache`
   *  - `-z`
   *  - `-R`
   *  - `--use-version`, if [[pmdUseVersion]] is not empty
   *  - `-r`, if [[pmdReportFile]] is not empty
   *  - `-f`
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
          ++ Seq("--cache", (Task.dest / "cache").toString) // for incremental analysis
          ++ Seq("-z", Task.ctx().workspace.toString) // to relativize paths in output report
          ++ Seq("-R", pmdRulesets().mkString(","))
          ++ pmdUseVersion().flatMap((lang, version) => Seq("--use-version", s"$lang-$version"))
          ++ pmdReportFile().iterator.flatMap(ref => Seq("-r", ref.path.toString))
          ++ Seq("-f", pmdFormat())
          ++ pmdInputs() // quirk: positional inputs require -f option in penultimate position
      val exitCode = Jvm.callProcess(
        mainClass = "net.sourceforge.pmd.cli.PmdCli",
        mainArgs = cliArgs,
        javaHome = javaHomeTask(),
        jvmArgs = pmdJavaOptions(),
        classPath = pmdClasspath().map(_.path),
        cwd = moduleDir, // to support relative paths in CLI args
        check = false,
        stdout = os.Inherit // for logging file violations
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
   * Defaults to line separated values in ''.pmd-use-version''. Each language and version pair in
   * a value must also be in separate lines.
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
   * Defaults to all sub-folders in workspace, except hidden folders and [[out]] folder.
   */
  override def pmdInputs = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      os.list.stream(moduleDir)
        .collect:
          case path if os.isDir(path) => path.last
        .filter(name => !(name.startsWith(".") || name == out))
        .toSeq
    }
  }

  /**
   * Defaults to line separated values in ''.pmd_java_opts''.
   */
  override def pmdJavaOptions = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      val file = moduleDir / ".pmd_java_opts"
      if os.exists(file) then os.read.lines(file) else Seq()
    }
  }
}
