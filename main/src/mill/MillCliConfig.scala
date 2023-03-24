package mill

import mainargs.{Flag, Leftover, arg}

class MillCliConfig private (
    @arg(
      short = 'h',
      doc =
        """(internal) The home directory of internally used Ammonite script engine;
           where it looks for config and caches."""
    )
    val home: os.Path,
    @arg(
      doc =
        """Run Mill in interactive mode and start a build REPL.
           This implies --no-server and no mill server will be used.
           Must be the first argument."""
    )
    val repl: Flag,
    @arg(
      name = "no-server",
      doc =
        """Run Mill in single-process mode.
           In this mode, no mill server will be started or used.
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
      name = "disable-ticker",
      doc =
        """Disable ticker log (e.g. short-lived prints of stages and progress bars)."""
    )
    val disableTicker: Flag,
    @arg(
      name = "enable-ticker",
      doc =
        """Enable ticker log (e.g. short-lived prints of stages and progress bars)."""
    )
    val enableTicker: Option[Boolean],
    @arg(name = "debug", short = 'd', doc = "Show debug output on STDOUT")
    val debugLog: Flag,
    @arg(
      name = "keep-going",
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
      name = "no-default-predef",
      doc = """Disable the default predef and run Mill with the minimal predef possible."""
    )
    val noDefaultPredef: Flag,
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
      name = "predef",
      short = 'p',
      doc =
        """Lets you load your predef from a custom location, rather than the
        "default location in your Ammonite home""")
    val predefFile: Option[os.Path],
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
    "noDefaultPredef" -> noDefaultPredef,
    "leftoverArgs" -> leftoverArgs,
    "color" -> color,
    "predefFile" -> predefFile
  ).map(p => s"${p._1}=${p._2}").mkString(getClass().getSimpleName + "(", ",", ")")
}

object MillCliConfig {
  /*
   * mainargs requires us to keep this apply method in sync with the private ctr of the class.
   * mainargs is designed to work with case classes,
   * but case classes can't be evolved in a binary compatible fashion.
   * mainargs parses the class ctr for itss internal model,
   * but used the companion's apply to actually create an instance of the config class,
   * hence we need both in sync.
   */
  def apply(
      home: os.Path = mill.api.Ctx.defaultHome,
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
      noDefaultPredef: Flag = Flag(),
      leftoverArgs: Leftover[String] = Leftover(),
      color: Option[Boolean] = None,
      predefFile: Option[os.Path] = None,
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
    noDefaultPredef = noDefaultPredef,
    leftoverArgs = leftoverArgs,
    color = color,
    predefFile = predefFile
  )
}

import mainargs.ParserForClass

// We want this in a separate source file, but to avoid stale --help output due
// to undercompilation, we have it in this file
// see https://github.com/com-lihaoyi/mill/issues/2315
object MillCliConfigParser {

  val customName = s"Mill Build Tool, version ${BuildInfo.millVersion}"
  val customDoc = "usage: mill [options] [[target [target-options]] [+ [target ...]]]"

  /**
   * Additional [[mainargs.TokensReader]] instance to teach it how to read Ammonite paths
   */
  implicit object PathRead
    extends mainargs.TokensReader[os.Path]("path", strs => Right(os.Path(strs.last, os.pwd)))


  private[this] lazy val parser: ParserForClass[MillCliConfig] =
    mainargs.ParserForClass[MillCliConfig]

  lazy val usageText =
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
