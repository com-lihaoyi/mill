package mill

import mainargs.{Flag, Leftover, arg}

class MillConfig private (
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
    @arg(name = "version", short = 'v', doc = "Show mill version and exit.")
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
    @arg(doc = "Print this help message.")
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
      name = "rest",
      doc =
        """The name of the targets you want to build, 
           followed by any parameters you wish to pass to those targets."""
    )
    val leftoverArgs: Leftover[String]
)

object MillConfig {
  def apply(
      home: os.Path = mill.api.Ctx.defaultHome,
      repl: Flag = Flag(),
      noServer: Flag = Flag(),
      bsp: Flag = Flag(),
      showVersion: Flag = Flag(),
      ringBell: Flag = Flag(),
      disableTicker: Flag = Flag(),
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
      leftoverArgs: Leftover[String] = Leftover()
  ): MillConfig = new MillConfig(
    home = home,
    repl = repl,
    noServer = noServer,
    bsp = bsp,
    showVersion = showVersion,
    ringBell = ringBell,
    disableTicker = disableTicker,
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
    leftoverArgs = leftoverArgs
  )
}

import mainargs.ParserForClass
import ammonite.repl.tools.Util.PathRead

// We want this in a separate source file, but to avoid stale --help output due
// to undercompilation, we have it in this file
// see https://github.com/com-lihaoyi/mill/issues/2315
object MillConfigParser {

  val customName = s"Mill Build Tool, version ${BuildInfo.millVersion}"
  val customDoc = "usage: mill [options] [[target [target-options]] [+ [target ...]]]"

  private[this] lazy val parser: ParserForClass[MillConfig] = mainargs.ParserForClass[MillConfig]

  lazy val usageText =
    parser.helpText(customName = customName, customDoc = customDoc)

  def parse(args: Array[String]): Either[String, MillConfig] = {
    parser.constructEither(
      args.toIndexedSeq,
      allowRepeats = true,
      autoPrintHelpAndExit = None,
      customName = customName,
      customDoc = customDoc
    )
  }

}
