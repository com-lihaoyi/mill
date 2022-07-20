package mill

import mainargs.{Flag, Leftover, arg}

case class MillConfig(
    ammoniteCore: ammonite.main.Config.Core,
    @arg(
      short = 'h',
      doc =
        "The home directory of the REPL; where it looks for config and caches"
    )
    home: os.Path = mill.api.Ctx.defaultHome,
    @arg(
      doc =
        """Run Mill in interactive mode and start a build REPL. This implies --no-server and no
    mill server will be used. Must be the first argument."""
    )
    repl: Flag,
    @arg(
      name = "no-server",
      doc =
        """Run Mill in single-process mode. In this mode, no mill server will be started or used. Must be the first argument."""
    )
    noServer: Flag,
    @arg(
      doc = """Enable BSP server mode."""
    )
    bsp: Flag,
    @arg(
      short = 'i',
      doc =
        """Run Mill in interactive mode, suitable for opening REPLs and taking user
      input. This implies --no-server and no mill server will be used. Must be the first argument."""
    )
    interactive: Flag,
    @arg(name = "version", short = 'v', doc = "Show mill version and exit.")
    showVersion: Flag,
    @arg(
      name = "bell",
      short = 'b',
      doc =
        "Ring the bell once if the run completes successfully, twice if it fails."
    )
    ringBell: Flag,
    @arg(
      name = "disable-ticker",
      doc =
        "Disable ticker log (e.g. short-lived prints of stages and progress bars)"
    )
    disableTicker: Flag,
    @arg(name = "debug", short = 'd', doc = "Show debug output on STDOUT")
    debugLog: Flag,
    @arg(
      name = "keep-going",
      short = 'k',
      doc = "Continue build, even after build failures"
    )
    keepGoing: Flag,
    @arg(
      name = "define",
      short = 'D',
      doc = "Define (or overwrite) a system property"
    )
    extraSystemProperties: Map[String, String],
    @arg(
      name = "jobs",
      short = 'j',
      doc =
        """Allow processing N targets in parallel. Use 1 to disable parallel and 0 to
      use as much threads as available processors."""
    )
    threadCountRaw: Option[Int],
    @arg(
      name = "import",
      doc = """Additional ivy dependencies to load into mill, e.g. plugins."""
    )
    imports: Seq[String],
    @arg(
      name = "rest",
      doc =
        """The name of the targets you want to build, followed by any parameters
      you wish to pass to those targets."""
    )
    leftoverArgs: Leftover[String]
)
