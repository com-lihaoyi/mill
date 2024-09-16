package mill.runner

import mainargs.{Flag, Leftover, arg}

class MillCliConfig private (
    @arg(
      short = 'h',
      doc =
        """(internal) The home directory where Mill looks for config and caches."""
    )
    val home: os.Path,
    // We need to keep it, otherwise, a given --repl would be silently parsed as target and result in misleading error messages.
    // Instead we fail programmatically when this flag is set.
    @deprecated("No longer supported.", "Mill 0.11.0-M8")
    @arg(
      hidden = true,
      doc = """This flag is no longer supported."""
    )
    val repl: Flag,
    @arg(
      doc = """Run Mill in single-process mode without a background server. Must be the first argument."""
    )
    val noServer: Flag,
    @arg(doc = """Enable BSP server mode.""")
    val bsp: Flag,
    @arg(name = "version", short = 'v', doc = "Show mill version information and exit.")
    val showVersion: Flag,
    @arg(
      name = "bell",
      short = 'b',
      doc = """Ring the bell once if the run completes successfully, twice if it fails."""
    )
    val ringBell: Flag,
    @arg(
      doc =
        """Enable ticker log (e.g. short-lived prints of stages and progress bars)."""
    )

    val ticker: Option[Boolean],
    @arg(name = "debug", short = 'd', doc = "Show debug output on STDOUT")
    val debugLog: Flag,
    @arg(
      short = 'k',
      doc = """Continue build, even after build failures."""
    )
    val keepGoing: Flag,
    @arg(
      name = "define",
      short = 'D',
      doc = """Define (or overwrite) a system property."""
    )
    val extraSystemProperties: Map[String, String],
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
    val threadCountRaw: Option[Int],
    @arg(
      name = "import",
      doc = """Additional ivy dependencies to load into mill, e.g. plugins."""
    )
    val imports: Seq[String],
    @arg(
      short = 'i',
      doc =
        """Run Mill in interactive mode, suitable for opening REPLs and taking user input.
          This implies --no-server and no mill server will be used. Must be the first argument."""
    )
    val interactive: Flag,
    @arg(doc = "Print this help message and exit.")
    val help: Flag,
    @arg(
      short = 'w',
      doc = """Watch and re-run your scripts when they change."""
    )
    val watch: Flag,
    @arg(
      short = 's',
      doc =
        """Make ivy logs during script import resolution go silent instead of printing;
           though failures will still throw exception."""
    )
    val silent: Flag,
    @arg(
      name = "target",
      doc =
        """The name or a pattern of the target(s) you want to build."""
    )
    val leftoverArgs: Leftover[String],
    @arg(
      doc =
        """Enable or disable colored output; by default colors are enabled in both REPL and scripts mode if
           the console is interactive, and disabled otherwise."""
    )
    val color: Option[Boolean],
    @arg(
      name = "disable-callgraph",
      doc = """
        Disables fine-grained invalidation of tasks based on analyzing code changes. If passed, you will
        need to manually run `clean` yourself after build changes.
      """
    )
    val disableCallgraph: Flag,
    @arg(
      doc =
        """Select a meta-build level to run the given targets. Level 0 is the normal project,
           level 1 the first meta-build, and so on. The last level is a synthetic meta-build used for bootstrapping"""
    )
    val metaLevel: Option[Int],
    @arg(doc = "Allows command args to be passed positionally without `--arg` by default")
    val allowPositional: Flag
) {
  override def toString: String = Seq(
    "home" -> home,
    "repl" -> repl,
    "noServer" -> noServer,
    "bsp" -> bsp,
    "showVersion" -> showVersion,
    "ringBell" -> ringBell,
    "ticker" -> ticker,
    "debugLog" -> debugLog,
    "keepGoing" -> keepGoing,
    "extraSystemProperties" -> extraSystemProperties,
    "threadCountRaw" -> threadCountRaw,
    "imports" -> imports,
    "interactive" -> interactive,
    "help" -> help,
    "watch" -> watch,
    "silent" -> silent,
    "leftoverArgs" -> leftoverArgs,
    "color" -> color,
    "disableCallgraph" -> disableCallgraph,
    "metaLevel" -> metaLevel,
    "allowPositional" -> allowPositional
  ).map(p => s"${p._1}=${p._2}").mkString(getClass().getSimpleName + "(", ",", ")")
}

object MillCliConfig {
  /*
   * mainargs requires us to keep this apply method in sync with the private ctr of the class.
   * mainargs is designed to work with case classes,
   * but case classes can't be evolved in a binary compatible fashion.
   * mainargs parses the class ctr for its internal model,
   * but uses the companion's apply to actually create an instance of the config class,
   * hence we need both in sync.
   */
  def apply(
      home: os.Path = mill.api.Ctx.defaultHome,
      @deprecated("No longer supported.", "Mill 0.11.0-M8")
      repl: Flag = Flag(),
      noServer: Flag = Flag(),
      bsp: Flag = Flag(),
      showVersion: Flag = Flag(),
      ringBell: Flag = Flag(),
      ticker: Option[Boolean] = None,
      debugLog: Flag = Flag(),
      keepGoing: Flag = Flag(),
      extraSystemProperties: Map[String, String] = Map(),
      threadCountRaw: Option[Int] = None,
      imports: Seq[String] = Seq(),
      interactive: Flag = Flag(),
      help: Flag = Flag(),
      watch: Flag = Flag(),
      silent: Flag = Flag(),
      leftoverArgs: Leftover[String] = Leftover(),
      color: Option[Boolean] = None,
      disableCallgraph: Flag = Flag(),
      metaLevel: Option[Int] = None,
      allowPositional: Flag = Flag()
  ): MillCliConfig = new MillCliConfig(
    home = home,
    repl = repl,
    noServer = noServer,
    bsp = bsp,
    showVersion = showVersion,
    ringBell = ringBell,
    ticker = ticker,
    debugLog = debugLog,
    keepGoing = keepGoing,
    extraSystemProperties = extraSystemProperties,
    threadCountRaw = threadCountRaw,
    imports = imports,
    interactive = interactive,
    help = help,
    watch = watch,
    silent = silent,
    leftoverArgs = leftoverArgs,
    color = color,
    disableCallgraph,
    metaLevel = metaLevel,
    allowPositional = allowPositional
  )
  @deprecated("Bin-compat shim", "Mill after 0.11.12")
  def apply(
      home: os.Path,
      @deprecated("No longer supported.", "Mill 0.11.0-M8")
      repl: Flag,
      noServer: Flag,
      bsp: Flag,
      showVersion: Flag,
      ringBell: Flag,
      ticker: Option[Boolean],
      debugLog: Flag,
      keepGoing: Flag,
      extraSystemProperties: Map[String, String],
      threadCountRaw: Option[Int],
      imports: Seq[String],
      interactive: Flag,
      help: Flag,
      watch: Flag,
      silent: Flag,
      leftoverArgs: Leftover[String],
      color: Option[Boolean],
      disableCallgraphInvalidation: Flag,
      metaLevel: Option[Int]
  ): MillCliConfig = new MillCliConfig(
    home = home,
    repl = repl,
    noServer = noServer,
    bsp = bsp,
    showVersion = showVersion,
    ringBell = ringBell,
    ticker = ticker,
    debugLog = debugLog,
    keepGoing = keepGoing,
    extraSystemProperties = extraSystemProperties,
    threadCountRaw = threadCountRaw,
    imports = imports,
    interactive = interactive,
    help = help,
    watch = watch,
    silent = silent,
    leftoverArgs = leftoverArgs,
    color = color,
    disableCallgraphInvalidation,
    metaLevel = metaLevel,
    allowPositional = Flag()
  )

  @deprecated("Bin-compat shim", "Mill after 0.11.0")
  private[runner] def apply(
      home: os.Path,
      @deprecated("No longer supported.", "Mill 0.11.0-M8")
      repl: Flag,
      noServer: Flag,
      bsp: Flag,
      showVersion: Flag,
      ringBell: Flag,
      ticker: Option[Boolean],
      debugLog: Flag,
      keepGoing: Flag,
      extraSystemProperties: Map[String, String],
      threadCountRaw: Option[Int],
      imports: Seq[String],
      interactive: Flag,
      help: Flag,
      watch: Flag,
      silent: Flag,
      noDefaultPredef: Flag,
      leftoverArgs: Leftover[String],
      color: Option[Boolean],
      predefFile: Option[os.Path]
  ): MillCliConfig = apply(
    home,
    repl,
    noServer,
    bsp,
    showVersion,
    ringBell,
    ticker,
    debugLog,
    keepGoing,
    extraSystemProperties,
    threadCountRaw,
    imports,
    interactive,
    help,
    watch,
    silent,
    leftoverArgs,
    color
  )
}

import mainargs.ParserForClass

// We want this in a separate source file, but to avoid stale --help output due
// to undercompilation, we have it in this file
// see https://github.com/com-lihaoyi/mill/issues/2315
object MillCliConfigParser {
  val customName: String = s"Mill Build Tool, version ${mill.main.BuildInfo.millVersion}"
  val customDoc = """
usage: mill [options] [[target [target-options]] [+ [target ...]]]

target cheat sheet:

./mill resolve _                 # see all top-level tasks and modules
./mill resolve __.compile        # see all `compile` tasks in any module (recursively)

./mill foo.bar.compile           # compile the module `foo.bar`

./mill foo.run --arg 1           # run the main method of the module `foo` and pass in `--arg 1`
./mill -i foo.console            # run the Scala console for the module `foo` (if it is a ScalaModule)

./mill foo.__.test               # run tests in module within `foo` (recursively)
./mill foo.test arg1 arg2 arg3   # run tests in the `foo` module passing in test arguments `arg1 arg2 arg3`
./mill foo.test + bar.test       # run tests in the `foo` module and `bar` module
./mill '{foo,bar,qux}.test'      # run tests in the `foo` module, `bar` module, and `qux` module

./mill foo.assembly              # generate an executable assembly of the module `foo`

./mill show foo.assembly         # print the output path of the assembly of module `foo`
./mill inspect foo.assembly      # show docs and metadata for the `assembly` task on module `foo`

./mill clean foo.assembly        # delete the output of `foo.assembly` to force re-evaluation
./mill clean                     # delete the output of the entire build to force force re-evaluation

./mill path foo.run foo.sources  # print the dependency chain showing how `foo.run` depends on `foo.sources`
./mill visualize __.compile      # show how the various `compile` tasks in each module depend on one another

options:"""

  import mill.api.JsonFormatters._

  private[this] lazy val parser: ParserForClass[MillCliConfig] =
    mainargs.ParserForClass[MillCliConfig]

  lazy val usageText: String = customName + "\n" + customDoc + parser.helpText(customName="", totalWidth=120)

  def parse(args: Array[String]): Either[String, MillCliConfig] = {
    parser.constructEither(
      args.toIndexedSeq,
      allowRepeats = true,
      autoPrintHelpAndExit = None,
      customName = customName,
      customDoc = customDoc
    )
  }

}
