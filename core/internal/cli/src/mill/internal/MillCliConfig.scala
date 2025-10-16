package mill.internal

import mainargs.{Flag, Leftover, arg}
import mill.api.JsonFormatters.*

case class MillCliConfig(
    // ==================== NORMAL CLI FLAGS ====================
    @arg(doc = "Run without a long-lived background daemon. Must be the first argument.")
    noDaemon: Flag = Flag(),
    @arg(name = "version", short = 'v', doc = "Show mill version information and exit.")
    showVersion: Flag = Flag(),
    @arg(
      name = "bell",
      short = 'b',
      doc = "Ring the bell once if the run completes successfully, twice if it fails."
    )
    ringBell: Flag = Flag(),
    @arg(
      doc =
        """Enable or disable the ticker log, which provides information on running
           tasks and where each log line came from"""
    )
    ticker: Option[Boolean] = None,
    @arg(name = "debug", short = 'd', doc = "Show debug output on STDOUT")
    debugLog: Flag = Flag(),
    @arg(
      short = 'k',
      doc = """Continue build, even after build failures."""
    )
    keepGoing: Flag = Flag(),
    @arg(
      name = "define",
      short = 'D',
      doc = """Define (or overwrite) a system property."""
    )
    extraSystemProperties: Map[String, String] = Map(),
    @arg(
      name = "jobs",
      short = 'j',
      doc =
        """The number of parallel threads. It can be an integer e.g. `5`
           meaning 5 threads, an expression e.g. `0.5C` meaning
           half as many threads as available cores, or `C-2`
           meaning 2 threads less than the number of cores. `1` disables
           parallelism and `0` (the default) uses 1 thread per core."""
    )
    threadCountRaw: Option[String] = None,
    @arg(
      name = "import",
      doc = """Additional ivy dependencies to load into mill, e.g. plugins."""
    )
    imports: Seq[String] = Nil,
    @arg(
      short = 'i',
      doc =
        """Run Mill in interactive mode, suitable for opening REPLs and taking user input.
          Identical to --no-daemon. Must be the first argument."""
    )
    interactive: Flag = Flag(),
    @arg(doc = "Print this help message and exit.")
    help: Flag,
    @arg(doc = "Print a internal or advanced command flags not intended for common usage")
    helpAdvanced: Flag,
    @arg(short = 'w', doc = "Watch and re-run the given tasks when when their inputs change.")
    watch: Flag = Flag(),
    @arg(
      name = "notify-watch",
      doc = "Use filesystem based file watching instead of polling based one (defaults to true)."
    )
    watchViaFsNotify: Boolean = true,
    @arg(name = "task", doc = "The name or a query of the tasks(s) you want to build.")
    leftoverArgs: Leftover[String] = Leftover(),
    @arg(doc =
      """Toggle colored output; by default enabled only if the console is interactive
         and NO_COLOR environment variable is not set"""
    )
    color: Option[Boolean] = None,
    @arg(
      doc =
        """Select a meta-level to run the given tasks. Level 0 is the main project in `build.mill`,
           level 1 the first meta-build in `mill-build/build.mill`, etc."""
    )
    metaLevel: Option[Int] = None,

    // ==================== ADVANCED CLI FLAGS ====================
    @arg(doc = "Allows command args to be passed positionally without `--arg` by default")
    allowPositional: Flag = Flag(),
    @arg(hidden = true, doc = """Enable BSP server mode.""")
    bsp: Flag,
    @arg(hidden = true, doc = """Create mill-bsp.json with Mill details under .bsp/""")
    bspInstall: Flag,
    @arg(
      hidden = true,
      doc =
        """Automatically reload the build when its sources change when running the BSP server (defaults to true)."""
    )
    bspWatch: Boolean = true,
    @arg(
      hidden = true,
      doc =
        """Evaluate tasks / commands without acquiring an exclusive lock on the Mill output directory"""
    )
    noBuildLock: Flag = Flag(),
    @arg(
      hidden = true,
      doc =
        """Do not wait for an exclusive lock on the Mill output directory to evaluate tasks / commands."""
    )
    noWaitForBuildLock: Flag = Flag(),
    @arg(
      doc = """
        Try to work offline.
        This tells modules that support it to work offline and avoid any access to the internet.
        This is on a best effort basis.
        There are currently no guarantees that modules don't attempt to fetch remote sources.
      """
    )
    offline: Flag = Flag(),
    @arg(
      doc = """
        Globally disables the checks that prevent you from reading and writing to disallowed 
        files or folders during evaluation. Useful as an escape hatch in case you desperately
        need to do something unusual and you are willing to take the risk
      """
    )
    noFilesystemChecker: Flag = Flag(),
    @arg(
      doc = """Runs Mill in tab-completion mode"""
    )
    tabComplete: Flag = Flag(),

    // ==================== DEPRECATED CLI FLAGS ====================
    @arg(hidden = true, short = 'h', doc = "Unsupported")
    home: os.Path = os.home,
    @arg(hidden = true, doc = "Unsupported")
    repl: Flag = Flag(),
    @arg(hidden = true, doc = "Unsupported")
    noServer: Flag = Flag(),
    @arg(short = 's', doc = "Unsupported")
    silent: Flag = Flag(),
    @arg(name = "disable-callgraph", doc = "Unsupported")
    disableCallgraph: Flag = Flag(),
    @arg(hidden = true, doc = "Unsupported")
    disablePrompt: Flag = Flag(),
    @arg(hidden = true, doc = "Unsupported")
    enableTicker: Option[Boolean] = None,
    @arg(hidden = true, doc = "Unsupported")
    disableTicker: Flag
) {
  def noDaemonEnabled =
    Seq(interactive.value, repl.value, noDaemon.value, noServer.value, bsp.value).count(identity)
}

import mainargs.ParserForClass

// We want this in a separate source file, but to avoid stale --help output due
// to under-compilation, we have it in this file
// see https://github.com/com-lihaoyi/mill/issues/2315
object MillCliConfig {
  val customName: String = s"Mill Build Tool, version ${mill.util.BuildInfo.millVersion}"
  val customDoc = """
Usage: mill [options] task [task-options] [+ task ...]
"""
  val cheatSheet =
    """
task cheat sheet:
  mill resolve _                 # see all top-level tasks and modules
  mill resolve __.compile        # see all `compile` tasks in any module (recursively)

  mill foo.bar.compile           # compile the module `foo.bar`

  mill foo.run --arg 1           # run the main method of the module `foo` and pass in `--arg 1`
  mill -i foo.console            # run the Scala console for the module `foo` (if it is a ScalaModule)

  mill foo.__.test               # run tests in modules nested within `foo` (recursively)
  mill foo.test arg1 arg2        # run tests in the `foo` module passing in test arguments `arg1 arg2`
  mill foo.test + bar.test       # run tests in the `foo` module and `bar` module
  mill '{foo,bar,qux}.test'      # run tests in the `foo` module, `bar` module, and `qux` module

  mill foo.assembly              # generate an executable assembly of the module `foo`
  mill show foo.assembly         # print the output path of the assembly of module `foo`
  mill inspect foo.assembly      # show docs and metadata for the `assembly` task on module `foo`

  mill clean foo.assembly        # delete the output of `foo.assembly` to force re-evaluation
  mill clean                     # delete the output of the entire build to force re-evaluation

  mill path foo.run foo.sources  # print the task chain showing how `foo.run` depends on `foo.sources`
  mill visualize __.compile      # show how the `compile` tasks in each module depend on one another

options:
"""

  lazy val parser: ParserForClass[MillCliConfig] = mainargs.ParserForClass[MillCliConfig]

  private lazy val helpAdvancedParser: ParserForClass[MillCliConfig] = new ParserForClass(
    parser.main.copy(argSigs0 = parser.main.argSigs0.collect {
      case a if !a.doc.contains("Unsupported") && a.hidden =>
        a.copy(
          hidden = false,
          // Hack to work around `a.copy` not propagating the name mapping correctly, so we have
          // to manually map the name ourselves. Doesn't affect runtime behavior since this is
          // just used for --help-advanced printing and not for argument parsing
          unMappedName = a.mappedName(mainargs.Util.kebabCaseNameMapper)
        )
    }),
    parser.companion
  )

  lazy val shortUsageText: String =
    "Please specify a task to evaluate\n" +
      customDoc +
      "\nRun `mill --help` for more details"

  lazy val longUsageText: String =
    customName +
      customDoc +
      cheatSheet +
      parser.helpText(customName = "", totalWidth = 100).stripPrefix("\n") +
      "\nPlease see the documentation at https://mill-build.org for more details,\n" +
      "or `./mill --help-advanced` for a list of advanced flags"

  lazy val helpAdvancedUsageText: String =
    customName +
      customDoc +
      helpAdvancedParser.helpText(customName = "", totalWidth = 100).stripPrefix("\n") +
      "\nAdvanced or internal command-line flags not intended for common usage. Use at your own risk!"

  def parse(args: Array[String]): mill.api.Result[MillCliConfig] = {
    mill.api.Result.fromEither(parser.constructEither(
      args.toIndexedSeq,
      allowRepeats = true,
      autoPrintHelpAndExit = None,
      customName = customName,
      customDoc = customDoc
    ))
  }

}
