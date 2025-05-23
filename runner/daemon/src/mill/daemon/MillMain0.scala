package mill.daemon

import mill.api.internal.{BspServerResult, internal}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.bsp.BSP
import mill.client.lock.{DoubleLock, Lock}
import mill.constants.{DaemonFiles, OutFiles, Util}
import mill.define.BuildCtx
import mill.internal.{Colors, MultiStream, PrefixLogger, PromptLogger, SimpleLogger}
import mill.server.Server
import mill.util.BuildInfo
import mill.{api, define}

import java.io.{InputStream, PipedInputStream, PrintStream}
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Properties, Using}

@internal
object MillMain0 {

  def handleMillException[T](
      err: PrintStream,
      onError: => T
  ): PartialFunction[Throwable, (Boolean, T)] = {
    case e: MillException =>
      err.println(e.getMessage())
      (false, onError)
    case e: InvocationTargetException
        if e.getCause != null && e.getCause.isInstanceOf[MillException] =>
      err.println(e.getCause.getMessage())
      (false, onError)
    case NonFatal(e) =>
      err.println("An unexpected error occurred " + e + "\n" + e.getStackTrace.mkString("\n"))
      throw e
      (false, onError)
  }

  def main(args: Array[String]): Unit = mill.define.SystemStreams.withTopLevelSystemStreamProxy {
    val initialSystemStreams = mill.define.SystemStreams.original

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val processId = Server.computeProcessId()
    val out = os.Path(OutFiles.out, BuildCtx.workspaceRoot)
    Server.watchProcessIdFile(
      out / OutFiles.millNoDaemon / processId / DaemonFiles.processId,
      processId,
      running = () => true,
      exit = msg => {
        System.err.println(msg)
        System.exit(0)
      }
    )

    val outLock = new DoubleLock(
      outMemoryLock,
      Lock.file((out / OutFiles.millOutLock).toString)
    )

    val daemonDir = os.Path(args.head)
    val (result, _) =
      try main0(
          args = args.tail,
          stateCache = RunnerState.empty,
          mainInteractive = mill.constants.Util.hasConsole(),
          streams0 = initialSystemStreams,
          env = System.getenv().asScala.toMap,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          systemExit = i => sys.exit(i),
          daemonDir = daemonDir,
          outLock = outLock
        )
      catch handleMillException(initialSystemStreams.err, ())

    System.exit(if (result) 0 else 1)
  }

  val outMemoryLock = Lock.memory()

  private def withStreams[T](
      bspMode: Boolean,
      streams: SystemStreams
  )(thunk: SystemStreams => T): T =
    if (bspMode) {
      // In BSP mode, don't let anything other than the BSP server write to stdout and read from stdin

      val outFileStream = os.write.outputStream(
        BuildCtx.workspaceRoot / OutFiles.out / "mill-bsp/out.log",
        createFolders = true
      )
      val errFileStream = os.write.outputStream(
        BuildCtx.workspaceRoot / OutFiles.out / "mill-bsp/err.log",
        createFolders = true
      )

      try {
        val streams0 = new SystemStreams(
          out = streams.err,
          err = streams.err,
          in = InputStream.nullInputStream()
        )
        mill.define.SystemStreams.withStreams(streams0) {
          thunk(streams0)
        }
      } finally {
        errFileStream.close()
        outFileStream.close()
      }
    } else
      mill.define.SystemStreams.withStreams(streams) {
        thunk(streams)
      }

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams0: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties0: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing,
      daemonDir: os.Path,
      outLock: Lock
  ): (Boolean, RunnerState) =
    mill.api.internal.MillScalaParser.current.withValue(MillScalaParserImpl) {
      os.SubProcess.env.withValue(env) {
        val parserResult = MillCliConfig.parse(args)
        // Detect when we're running in BSP mode as early as possible,
        // and ensure we don't log to the default stdout or use the default
        // stdin, meant to be used for BSP JSONRPC communication, where those
        // logs would be lost.
        // This is especially helpful if anything unexpectedly goes wrong
        // early on, when developing on Mill or debugging things for example.
        val bspMode = parserResult.toOption.exists(_.bsp.value)
        withStreams(bspMode, streams0) { streams =>
          parserResult match {
            // Cannot parse args
            case Result.Failure(msg) =>
              streams.err.println(msg)
              (false, RunnerState.empty)

            case Result.Success(config) if config.help.value =>
              streams.out.println(MillCliConfig.longUsageText)
              (true, RunnerState.empty)

            case Result.Success(config) if config.showVersion.value =>
              def prop(k: String) = System.getProperty(k, s"<unknown $k>")

              val javaVersion = prop("java.version")
              val javaVendor = prop("java.vendor")
              val javaHome = prop("java.home")
              val fileEncoding = prop("file.encoding")
              val osName = prop("os.name")
              val osVersion = prop("os.version")
              val osArch = prop("os.arch")
              streams.out.println(
                s"""Mill Build Tool version ${BuildInfo.millVersion}
                   |Java version: $javaVersion, vendor: $javaVendor, runtime: $javaHome
                   |Default locale: ${Locale.getDefault()}, platform encoding: $fileEncoding
                   |OS name: "$osName", version: $osVersion, arch: $osArch""".stripMargin
              )
              (true, RunnerState.empty)

            case Result.Success(config)
                if config.noDaemonEnabled > 0 && streams.in.getClass == classOf[PipedInputStream] =>
              // because we have stdin as dummy, we assume we were already started in server process
              streams.err.println(
                "-i/--interactive/--no-daemon/--bsp must be passed in as the first argument"
              )
              (false, RunnerState.empty)

            case Result.Success(config) if config.noDaemonEnabled > 1 =>
              streams.err.println(
                "Only one of -i/--interactive, --no-daemon or --bsp may be given"
              )
              (false, RunnerState.empty)

            // Check non-negative --meta-level option
            case Result.Success(config) if config.metaLevel.exists(_ < 0) =>
              streams.err.println("--meta-level cannot be negative")
              (false, RunnerState.empty)

            case Result.Success(config) =>
              val noColorViaEnv = env.get("NO_COLOR").exists(_.nonEmpty)
              val colored = config.color.getOrElse(mainInteractive && !noColorViaEnv)
              val colors =
                if (colored) mill.internal.Colors.Default else mill.internal.Colors.BlackWhite

              if (!config.silent.value) {
                checkMillVersionFromFile(BuildCtx.workspaceRoot, streams.err)
              }

              val maybeThreadCount =
                parseThreadCount(config.threadCountRaw, Runtime.getRuntime.availableProcessors())

              // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
              val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty
              val bspInstallModeJobCountOpt = {
                def defaultJobCount =
                  maybeThreadCount.toOption.getOrElse(BSP.defaultJobCount)

                val viaEmulatedExternalCommand = Option.when(
                  !config.bsp.value &&
                    config.leftoverArgs.value.headOption.contains("mill.bsp.BSP/install")
                ) {
                  config.leftoverArgs.value.tail match {
                    case Seq() => defaultJobCount
                    case Seq("--jobs", value) =>
                      val asIntOpt = value.toIntOption
                      asIntOpt.getOrElse {
                        streams.err.println(
                          s"Warning: ignoring --jobs value passed to ${config.leftoverArgs.value.head}"
                        )
                        defaultJobCount
                      }
                    case other =>
                      streams.err.println(
                        s"Warning: ignoring leftover arguments passed to ${config.leftoverArgs.value.head}"
                      )
                      defaultJobCount
                  }
                }

                viaEmulatedExternalCommand.orElse {
                  Option.when(config.bspInstall.value)(defaultJobCount)
                }
              }

              val (success, nextStateCache) = {
                if (config.repl.value) {
                  streams.err.println("The --repl mode is no longer supported.")
                  (false, stateCache)

                } else if (bspInstallModeJobCountOpt.isDefined) {
                  BSP.install(bspInstallModeJobCountOpt.get, config.debugLog.value, streams.err)
                  (true, stateCache)
                } else if (!bspMode && config.leftoverArgs.value.isEmpty) {
                  println(MillCliConfig.shortUsageText)

                  (true, stateCache)

                } else if (maybeThreadCount.errorOpt.isDefined) {
                  streams.err.println(maybeThreadCount.errorOpt.get)
                  (false, stateCache)

                } else {
                  val userSpecifiedProperties =
                    userSpecifiedProperties0 ++ config.extraSystemProperties

                  val threadCount = Some(maybeThreadCount.toOption.get)

                  val out = os.Path(OutFiles.out, BuildCtx.workspaceRoot)
                  Using.resource(new TailManager(daemonDir)) { tailManager =>
                    def runMillBootstrap(
                        enterKeyPressed: Boolean,
                        prevState: Option[RunnerState],
                        targetsAndParams: Seq[String],
                        streams: SystemStreams,
                        millActiveCommandMessage: String,
                        loggerOpt: Option[Logger] = None
                    ) = Server.withOutLock(
                      config.noBuildLock.value,
                      config.noWaitForBuildLock.value,
                      out,
                      millActiveCommandMessage,
                      streams,
                      outLock
                    ) {
                      def proceed(logger: Logger): Watching.Result[RunnerState] = {
                        // Enter key pressed, removing mill-selective-execution.json to
                        // ensure all tasks re-run even though no inputs may have changed
                        if (enterKeyPressed) os.remove(out / OutFiles.millSelectiveExecution)
                        mill.define.SystemStreams.withStreams(logger.streams) {
                          tailManager.withOutErr(logger.streams.out, logger.streams.err) {

                            new MillBuildBootstrap(
                              projectRoot = BuildCtx.workspaceRoot,
                              output = out,
                              keepGoing = config.keepGoing.value,
                              imports = config.imports,
                              env = env,
                              threadCount = threadCount,
                              targetsAndParams = targetsAndParams,
                              prevRunnerState = prevState.getOrElse(stateCache),
                              logger = logger,
                              needBuildFile = needBuildFile(config),
                              requestedMetaLevel = config.metaLevel,
                              config.allowPositional.value,
                              systemExit = systemExit,
                              streams0 = streams,
                              selectiveExecution = config.watch.value,
                              offline = config.offline.value
                            ).evaluate()
                          }

                        }
                      }

                      loggerOpt match {
                        case Some(logger) =>
                          proceed(logger)
                        case None =>
                          Using.resource(getLogger(
                            streams,
                            config,
                            enableTicker = config.ticker
                              .orElse(config.enableTicker)
                              .orElse(Option.when(config.disableTicker.value)(false)),
                            daemonDir,
                            colored = colored,
                            colors = colors
                          )) { logger =>
                            proceed(logger)
                          }
                      }
                    }

                    if (bspMode) {

                      Using.resource(getLogger(
                        streams,
                        config,
                        enableTicker = Some(false),
                        daemonDir,
                        colored = colored,
                        colors = colors
                      )) { _ =>
                        val bspLogger: Logger =
                          getBspLogger(streams, config)
                        runBspSession(
                          streams0,
                          streams,
                          prevRunnerState =>
                            val initCommandLogger = new PrefixLogger(bspLogger, Seq("init"))
                            runMillBootstrap(
                              false,
                              prevRunnerState,
                              Seq("version"),
                              initCommandLogger.streams,
                              "BSP:initialize",
                              loggerOpt = Some(initCommandLogger)
                            ).result
                          ,
                          outLock,
                          bspLogger
                        )
                      }

                      (true, RunnerState(None, Nil, None))
                    } else if (
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/idea") ||
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/")
                    ) {
                      val runnerState =
                        runMillBootstrap(false, None, Seq("version"), streams, "BSP:initialize")
                      new mill.idea.GenIdeaImpl(
                        runnerState.result.frames.flatMap(_.evaluator)
                      ).run()
                      (true, RunnerState(None, Nil, None))
                    } else {
                      // When starting a --watch, clear the `mill-selective-execution.json`
                      // file, so that the first run always selects everything and only
                      // subsequent re-runs are selective depending on what changed.
                      if (config.watch.value) os.remove(out / OutFiles.millSelectiveExecution)
                      Watching.watchLoop(
                        ringBell = config.ringBell.value,
                        watch = Option.when(config.watch.value)(Watching.WatchArgs(
                          setIdle = setIdle,
                          colors,
                          useNotify = config.watchViaFsNotify,
                          daemonDir = daemonDir
                        )),
                        streams = streams,
                        evaluate = (enterKeyPressed: Boolean, prevState: Option[RunnerState]) => {
                          adjustJvmProperties(userSpecifiedProperties, initialSystemProperties)
                          runMillBootstrap(
                            enterKeyPressed,
                            prevState,
                            config.leftoverArgs.value,
                            streams,
                            config.leftoverArgs.value.mkString(" ")
                          )
                        }
                      )
                    }
                  }
                }
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
    }

  /**
   * Runs the BSP server, and exits when the server is done
   *
   * @param bspStreams Streams to use for BSP JSONRPC communication with the BSP client
   * @param logStreams Streams to use for logging
   * @param runMillBootstrap Load the Mill build, building / updating meta-builds along the way
   */
  def runBspSession(
      bspStreams: SystemStreams,
      logStreams: SystemStreams,
      runMillBootstrap: Option[RunnerState] => RunnerState,
      outLock: Lock,
      bspLogger: Logger
  ): Result[BspServerResult] = {

    bspLogger.info("Trying to load BSP server...")

    val wsRoot = BuildCtx.workspaceRoot
    val logDir = wsRoot / OutFiles.out / "mill-bsp"
    val bspServerHandleRes = {
      os.makeDir.all(logDir)
      mill.bsp.worker.BspWorkerImpl.startBspServer(
        define.BuildCtx.workspaceRoot,
        bspStreams,
        logDir,
        true,
        outLock,
        bspLogger
      )
    }

    bspLogger.info("BSP server started")

    val runSessionRes = bspServerHandleRes.flatMap { bspServerHandle =>
      try {
        var repeatForBsp = true
        var bspRes: Option[Result[BspServerResult]] = None
        var prevRunnerState: Option[RunnerState] = None
        while (repeatForBsp) {
          repeatForBsp = false
          val runnerState = runMillBootstrap(prevRunnerState)
          val runSessionRes =
            bspServerHandle.runSession(runnerState.frames.flatMap(_.evaluator))
          prevRunnerState = Some(runnerState)
          repeatForBsp = runSessionRes == BspServerResult.ReloadWorkspace
          bspRes = Some(runSessionRes)
          bspLogger.info(s"BSP session returned with $runSessionRes")
        }

        // should make the lsp4j-managed BSP server exit
        bspStreams.in.close()

        bspRes.get
      } finally bspServerHandle.close()
    }

    bspLogger.info(
      s"Exiting BSP runner loop. Stopping BSP server. Last result: $runSessionRes"
    )
    runSessionRes
  }

  private[mill] def parseThreadCount(
      threadCountRaw: Option[String],
      availableCores: Int
  ): Result[Int] = {
    def err(detail: String) =
      s"Invalid value \"${threadCountRaw.getOrElse("")}\" for flag -j/--jobs: $detail"

    (threadCountRaw match {
      case None => Result.Success(availableCores)
      case Some("0") => Result.Success(availableCores)
      case Some(s"${n}C") => Result.fromEither(n.toDoubleOption
          .toRight(err("Failed to find a float number before \"C\"."))
          .map(m => (m * availableCores).toInt))
      case Some(s"C-${n}") => Result.fromEither(n.toIntOption
          .toRight(err("Failed to find a int number after \"C-\"."))
          .map(availableCores - _))
      case Some(n) => Result.fromEither(n.toIntOption
          .toRight(err("Failed to find a int number")))
    }).map { x => if (x < 1) 1 else x }
  }

  def getLogger(
      streams: SystemStreams,
      config: MillCliConfig,
      enableTicker: Option[Boolean],
      daemonDir: os.Path,
      colored: Boolean,
      colors: Colors
  ): Logger & AutoCloseable = {
    new PromptLogger(
      colored = colored,
      enableTicker = enableTicker.getOrElse(true),
      infoColor = colors.info,
      warnColor = colors.warn,
      errorColor = colors.error,
      systemStreams0 = streams,
      debugEnabled = config.debugLog.value,
      titleText = config.leftoverArgs.value.mkString(" "),
      terminfoPath = daemonDir / DaemonFiles.terminfo,
      currentTimeMillis = () => System.currentTimeMillis()
    )
  }

  def getBspLogger(
      streams: SystemStreams,
      config: MillCliConfig
  ): Logger =
    new PrefixLogger(
      new SimpleLogger(
        streams,
        Seq("bsp"),
        debugEnabled = config.debugLog.value
      ),
      Nil
    )

  /**
   * Determine, whether we need a `build.mill` or not.
   */
  private def needBuildFile(config: MillCliConfig): Boolean = {
    // Tasks, for which running Mill without an existing buildfile is allowed.
    val noBuildFileTaskWhitelist = Seq(
      "init",
      "version",
      "mill.scalalib.giter8.Giter8Module/init"
    )
    val targetsAndParams = config.leftoverArgs.value
    val whitelistMatch =
      targetsAndParams.nonEmpty && noBuildFileTaskWhitelist.exists(targetsAndParams.head == _)
    // Has the user additional/extra imports
    // (which could provide additional commands that could make sense without a build.mill)
    val extraPlugins = config.imports.nonEmpty
    !(whitelistMatch || extraPlugins)
  }

  def readVersionFile(file: os.Path): Option[String] = file match {
    case f if os.exists(f) => os.read.lines(f).find(l => l.trim().nonEmpty)
    case _ => None
  }

  private val usingMillVersionPattern = "//[|] +mill-version: +([^ ]+) *$".r

  def readUsingMillVersionFile(file: os.Path): Option[String] = file match {
    case f if os.exists(f) =>
      os.read.lines(f)
        .takeWhile(_.startsWith("//|"))
        .collectFirst {
          case usingMillVersionPattern(version) => version
        }
    case _ => None
  }

  def readBestMillVersion(projectDir: os.Path): Option[(os.Path, String)] = {
    val versionFiles = Seq(".mill-version", ".config/mill-version")
      .map(f => (projectDir / os.RelPath(f), readVersionFile))
    val usingFiles = mill.constants.CodeGenConstants.rootBuildFileNames.asScala
      .map(f => (projectDir / os.RelPath(f), readUsingMillVersionFile))

    (versionFiles ++ usingFiles)
      .foldLeft(None: Option[(os.Path, String)]) {
        case (l, (file, readVersion)) => l.orElse(readVersion(file).map(version => (file, version)))
      }
  }

  def checkMillVersionFromFile(projectDir: os.Path, stderr: PrintStream): Unit = {
    readBestMillVersion(projectDir).foreach { case (file, version) =>
      if (BuildInfo.millVersion != version.stripSuffix("-native")) {
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
    val systemPropertiesToUnset = currentProps.keySet -- desiredProps.keySet

    for (k <- systemPropertiesToUnset) System.clearProperty(k)
    for ((k, v) <- desiredProps) System.setProperty(k, v)
  }
}
