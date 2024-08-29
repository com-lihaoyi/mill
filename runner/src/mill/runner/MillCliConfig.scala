package mill.runner

import mainargs.{Flag, Leftover, arg}
import mill.api.WorkspaceRoot
import os.Path

class MillCliConfig private (
    @arg(
      short = 'h',
      doc =
        """(internal) The home directory of internally used Ammonite script engine;
           where it looks for config and caches."""
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
      doc = """Run Mill in single-process mode.
               In this mode, no Mill server will be started or used.
               Must be the first argument."""
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
        """Disable ticker log (e.g. short-lived prints of stages and progress bars)."""
    )
    val disableTicker: Flag,
    @arg(
      doc =
        """Enable ticker log (e.g. short-lived prints of stages and progress bars)."""
    )

    val enableTicker: Option[Boolean],
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
        """Allow processing N targets in parallel.
           Use 1 to disable parallel and 0 to use as much threads as available processors."""
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
        """The name or a pattern of the target(s) you want to build,
           followed by any parameters you wish to pass to those targets.
           To specify multiple target names or patterns, use the `+` separator."""
    )
    val leftoverArgs: Leftover[String],
    @arg(
      doc =
        """Enable or disable colored output; by default colors are enabled
          in both REPL and scripts mode if the console is interactive, and disabled
          otherwise."""
    )
    val color: Option[Boolean],
    @arg(
      doc =
        """Disable the fine-grained callgraph-based target invalidation in response to
           code changes, and instead fall back to the previous coarse-grained implementation
           relying on the script `import $file` graph"""
    )
    val disableCallgraphInvalidation: Flag,
    @arg(
      doc =
        """Experimental: Select a meta-build level to run the given targets.
           Level 0 is the normal project, level 1 the first meta-build, and so on.
           The last level is the built-in synthetic meta-build which Mill uses to bootstrap the project."""
    )
    val metaLevel: Option[Int],
    @arg(
      doc =
        """"""
    )
    val allowPositionalCommandArgs: Flag
) {
  override def toString: String = Seq(
    "home" -> home,
    "repl" -> repl,
    "noServer" -> noServer,
    "bsp" -> bsp,
    "showVersion" -> showVersion,
    "ringBell" -> ringBell,
    "disableTicker" -> disableTicker,
    "enableTicker" -> enableTicker,
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
    "disableCallgraphInvalidation" -> disableCallgraphInvalidation,
    "metaLevel" -> metaLevel,
    "allowPositionalCommandArgs" -> allowPositionalCommandArgs
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
      disableTicker: Flag = Flag(),
      enableTicker: Option[Boolean] = None,
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
      disableCallgraphInvalidation: Flag = Flag(),
      metaLevel: Option[Int] = None,
      allowPositionalCommandArgs: Flag = Flag()
  ): MillCliConfig = new MillCliConfig(
    home = home,
    repl = repl,
    noServer = noServer,
    bsp = bsp,
    showVersion = showVersion,
    ringBell = ringBell,
    disableTicker = disableTicker,
    enableTicker = enableTicker,
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
    allowPositionalCommandArgs = allowPositionalCommandArgs
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
      disableTicker: Flag,
      enableTicker: Option[Boolean],
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
    disableTicker = disableTicker,
    enableTicker = enableTicker,
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
    allowPositionalCommandArgs = Flag()
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
      disableTicker: Flag,
      enableTicker: Option[Boolean],
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
    disableTicker,
    enableTicker,
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
  val customDoc = "usage: mill [options] [[target [target-options]] [+ [target ...]]]"

  /**
   * Additional [[mainargs.TokensReader]] instance to teach it how to read Ammonite paths
   */
  implicit object PathRead extends mainargs.TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(strs: Seq[String]): Either[String, Path] =
      Right(os.Path(strs.last, WorkspaceRoot.workspaceRoot))
  }

  private[this] lazy val parser: ParserForClass[MillCliConfig] =
    mainargs.ParserForClass[MillCliConfig]

  lazy val usageText: String =
    parser.helpText(customName = customName, customDoc = customDoc)

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
