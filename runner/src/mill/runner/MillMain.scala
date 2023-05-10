package mill.runner
import mill.BuildInfo

import java.io.{FileOutputStream, PrintStream}
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Properties
import mill.java9rtexport.Export
import mill.api.{DummyInputStream, internal}
import mill.main.BspServerResult
import mill.api.SystemStreams
import mill.util.{PrintLogger, Util}

@internal
object MillMain {
  def main(args: Array[String]): Unit = {
    val initialSystemStreams = new SystemStreams(System.out, System.err, System.in)
    // setup streams
    val (runnerStreams, cleanupStreams, bspLog) =
      if (args.headOption == Option("--bsp")) {
        // In BSP mode, we use System.in/out for protocol communication
        // and all Mill output (stdout and stderr) goes to a dedicated file
        val stderrFile = os.pwd / ".bsp" / "mill-bsp.stderr"
        os.makeDir.all(stderrFile / os.up)
        val errFile = new PrintStream(new FileOutputStream(stderrFile.toIO, true))
        val errTee = new TeePrintStream(initialSystemStreams.err, errFile)
        val msg = s"Mill in BSP mode, version ${BuildInfo.millVersion}, ${new java.util.Date()}"
        errTee.println(msg)
        (
          new SystemStreams(
            // out is used for the protocol
            out = initialSystemStreams.out,
            // err is default, but also tee-ed into the bsp log file
            err = errTee,
            in = System.in
          ),
          Seq(errFile),
          Some(errFile)
        )
      } else {
        // Unchanged system stream
        (initialSystemStreams, Seq(), None)
      }

    if (Properties.isWin && System.console() != null)
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val (result, _) =
      try main0(
          args = args,
          stateCache = RunnerState.empty,
          mainInteractive = mill.util.Util.isInteractive(),
          streams0 = runnerStreams,
          bspLog = bspLog,
          env = System.getenv().asScala.toMap,
          setIdle = b => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap
        )
      finally {
        cleanupStreams.foreach(_.close())
      }
    System.exit(if (result) 0 else 1)
  }

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams0: SystemStreams,
      bspLog: Option[PrintStream],
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties0: Map[String, String],
      initialSystemProperties: Map[String, String]
  ): (Boolean, RunnerState) = {
    val printLoggerState = new PrintLogger.State()
    val streams = PrintLogger.wrapSystemStreams(streams0, printLoggerState)
    SystemStreams.withStreams(streams) {
      MillCliConfigParser.parse(args) match {
        // Cannot parse args
        case Left(msg) =>
          streams.err.println(msg)
          (false, RunnerState.empty)

        case Right(config) if config.help.value =>
          streams.out.println(MillCliConfigParser.usageText)
          (true, RunnerState.empty)

        case Right(config) if config.showVersion.value =>
          def p(k: String, d: String = "<unknown>") = System.getProperty(k, d)
          streams.out.println(
            s"""Mill Build Tool version ${BuildInfo.millVersion}
               |Java version: ${p("java.version", "<unknown Java version")}, vendor: ${p(
                "java.vendor",
                "<unknown Java vendor"
              )}, runtime: ${p("java.home", "<unknown runtime")}
               |Default locale: ${Locale.getDefault()}, platform encoding: ${p(
                "file.encoding",
                "<unknown encoding>"
              )}
               |OS name: "${p("os.name")}", version: ${p("os.version")}, arch: ${p(
                "os.arch"
              )}""".stripMargin
          )
          (true, RunnerState.empty)

        case Right(config)
            if (
              config.interactive.value || config.repl.value || config.noServer.value || config.bsp.value
            ) && streams.in == DummyInputStream =>
          // because we have stdin as dummy, we assume we were already started in server process
          streams.err.println(
            "-i/--interactive/--repl/--no-server/--bsp must be passed in as the first argument"
          )
          (false, RunnerState.empty)

        case Right(config)
            if Seq(
              config.interactive.value,
              config.repl.value,
              config.noServer.value,
              config.bsp.value
            ).count(identity) > 1 =>
          streams.err.println(
            "Only one of -i/--interactive, --repl, --no-server or --bsp may be given"
          )
          (false, RunnerState.empty)

        case Right(config) =>
          val logger = getLogger(
            streams,
            config,
            mainInteractive,
            enableTicker = if (config.disableTicker.value) Some(false) else config.enableTicker,
            printLoggerState
          )
          if (!config.silent.value) {
            checkMillVersionFromFile(os.pwd, streams.err)
          }

          val useRepl =
            config.repl.value || (config.interactive.value && config.leftoverArgs.value.isEmpty)

          // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
          val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty

          val (success, nextStateCache) =
            if (config.repl.value && config.leftoverArgs.value.nonEmpty) {
              logger.error("No target may be provided with the --repl flag")
              (false, stateCache)
              //          } else if(config.bsp.value && config.leftoverArgs.value.nonEmpty) {
              //            stderr.println("No target may be provided with the --bsp flag")
              //            (false, stateCache)
            } else if (config.leftoverArgs.value.isEmpty && config.noServer.value) {
              logger.error(
                "A target must be provided when not starting a build REPL"
              )
              (false, stateCache)
            } else if (useRepl && streams.in == DummyInputStream) {
              logger.error(
                "Build REPL needs to be run with the -i/--interactive/--repl flag"
              )
              (false, stateCache)
            } else {
              if (useRepl && config.interactive.value) {
                logger.error(
                  "WARNING: Starting a build REPL without --repl is deprecated"
                )
              }
              val userSpecifiedProperties =
                userSpecifiedProperties0 ++ config.extraSystemProperties

              val threadCount = config.threadCountRaw match {
                case None => Some(1)
                case Some(0) => None
                case Some(n) => Some(n)
              }

              if (mill.main.client.Util.isJava9OrAbove) {
                val rt = config.home / Export.rtJarName
                if (!os.exists(rt)) {
                  println(
                    s"Preparing Java ${System.getProperty("java.version")} runtime; this may take a minute or two ..."
                  )
                  Export.rtTo(rt.toIO, false)
                }
              }

              val bspContext =
                if (bspMode) Some(new BspContext(streams, bspLog, config.home)) else None
              val targetsAndParams =
                bspContext.map(_.millArgs).getOrElse(config.leftoverArgs.value.toList)

              var repeatForBsp = true
              var loopRes: (Boolean, RunnerState) = (false, RunnerState.empty)
              while (repeatForBsp) {
                repeatForBsp = false

                val (isSuccess, evalStateOpt) = Watching.watchLoop(
                  logger = logger,
                  ringBell = config.ringBell.value,
                  watch = config.watch.value,
                  streams = streams,
                  setIdle = setIdle,
                  evaluate = (prevState: Option[RunnerState]) => {
                    adjustJvmProperties(userSpecifiedProperties, initialSystemProperties)

                    new MillBuildBootstrap(
                      projectRoot = os.pwd,
                      home = config.home,
                      keepGoing = config.keepGoing.value,
                      imports = config.imports,
                      env = env,
                      threadCount = threadCount,
                      targetsAndParams = targetsAndParams,
                      prevRunnerState = prevState.getOrElse(stateCache),
                      logger = logger
                    ).evaluate()
                  }
                )

                bspContext.foreach { ctx =>
                  repeatForBsp = ctx.handle.lastResult == Some(BspServerResult.ReloadWorkspace)
                  logger.error(
                    s"`${ctx.millArgs.mkString(" ")}` returned with ${ctx.handle.lastResult}"
                  )
                }
                loopRes = (isSuccess, evalStateOpt)
              } // while repeatForBsp
              bspContext.foreach { ctx =>
                logger.error(
                  s"Exiting BSP runner loop. Stopping BSP server. Last result: ${ctx.handle.lastResult}"
                )
                ctx.handle.stop()
              }
              loopRes

            }
          if (config.ringBell.value) {
            if (success) println("\u0007")
            else {
              println("\u0007")
              Thread.sleep(250)
              println("\u0007")
            }
          }
          (success, nextStateCache)
      }
    }
  }

  def getLogger(
      streams: SystemStreams,
      config: MillCliConfig,
      mainInteractive: Boolean,
      enableTicker: Option[Boolean],
      printLoggerState: PrintLogger.State
  ) = {
    val colored = config.color.getOrElse(mainInteractive)
    val colors = if (colored) mill.util.Colors.Default else mill.util.Colors.BlackWhite

    val logger = new mill.util.PrintLogger(
      colored = colored,
      enableTicker = enableTicker.getOrElse(mainInteractive),
      infoColor = colors.info,
      errorColor = colors.error,
      systemStreams = streams,
      debugEnabled = config.debugLog.value,
      context = "",
      printLoggerState
    )
    logger
  }

  def checkMillVersionFromFile(projectDir: os.Path, stderr: PrintStream) = {
    Seq(
      projectDir / ".config" / "mill-version",
      projectDir / ".mill-version"
    ).collectFirst {
      case f if os.exists(f) =>
        (f, os.read.lines(f).filter(l => l.trim().nonEmpty).headOption)
    }.foreach { case (file, Some(version)) =>
      if (BuildInfo.millVersion != version) {
        val msg =
          s"""Mill version ${BuildInfo.millVersion} is different than configured for this directory!
             |Configured version is ${version} (${file})""".stripMargin
        stderr.println(
          msg
        )
      }
    }
  }

  def adjustJvmProperties(
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String]
  ): Unit = {
    val currentProps = sys.props
    val desiredProps = initialSystemProperties ++ userSpecifiedProperties
    val systemPropertiesToUnset = desiredProps.keySet -- currentProps.keySet

    for (k <- systemPropertiesToUnset) System.clearProperty(k)
    for ((k, v) <- desiredProps) System.setProperty(k, v)
  }
}
