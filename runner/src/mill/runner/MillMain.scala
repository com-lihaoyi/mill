package mill.runner

import mill.api
import mill.api.internal.{BspServerResult, internal}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.bsp.BSP
import mill.constants.{OutFiles, ServerFiles, Util}
import mill.define
import mill.define.WorkspaceRoot
import mill.util.BuildInfo
import mill.runner.meta.ScalaCompilerWorker
import mill.internal.{Colors, MultiStream, PromptLogger}
import mill.server.Server

import java.io.{InputStream, PipedInputStream, PrintStream}
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.Properties
import scala.util.Using

@internal
object MillMain {

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
    val out = os.Path(OutFiles.out, WorkspaceRoot.workspaceRoot)
    Server.watchProcessIdFile(
      out / OutFiles.millNoServer / processId / ServerFiles.processId,
      processId,
      running = () => true,
      exit = msg => {
        System.err.println(msg)
        System.exit(0)
      }
    )

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
          serverDir = os.Path(args.head)
        )
      catch handleMillException(initialSystemStreams.err, ())

    System.exit(if (result) 0 else 1)
  }

  lazy val maybeScalaCompilerWorker = ScalaCompilerWorker.bootstrapWorker()

  private def withStreams[T](
      bspMode: Boolean,
      streams: SystemStreams
  )(thunk: SystemStreams => T): T =
    if (bspMode) {
      // In BSP mode, don't let anything other than the BSP server write to stdout and read from stdin

      val outFileStream = os.write.outputStream(
        WorkspaceRoot.workspaceRoot / OutFiles.out / "mill-bsp/out.log",
        createFolders = true
      )
      val errFileStream = os.write.outputStream(
        WorkspaceRoot.workspaceRoot / OutFiles.out / "mill-bsp/err.log",
        createFolders = true
      )

      try {
        val streams0 = new SystemStreams(
          out = new MultiStream(streams.err, outFileStream),
          err = new MultiStream(streams.err, errFileStream),
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
      serverDir: os.Path
  ): (Boolean, RunnerState) = {

    os.SubProcess.env.withValue(env) {
      val parserResult = MillCliConfigParser.parse(args)
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
            streams.out.println(MillCliConfigParser.longUsageText)
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
              if (
                config.interactive.value || config.noServer.value || config.bsp.value
              ) && streams.in.getClass == classOf[PipedInputStream] =>
            // because we have stdin as dummy, we assume we were already started in server process
            streams.err.println(
              "-i/--interactive/--no-server/--bsp must be passed in as the first argument"
            )
            (false, RunnerState.empty)

          case Result.Success(config)
              if Seq(
                config.interactive.value,
                config.noServer.value,
                config.bsp.value
              ).count(identity) > 1 =>
            streams.err.println(
              "Only one of -i/--interactive, --no-server or --bsp may be given"
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
              checkMillVersionFromFile(WorkspaceRoot.workspaceRoot, streams.err)
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
                println(MillCliConfigParser.shortUsageText)

                (true, stateCache)

              } else if (maybeThreadCount.errorOpt.isDefined) {
                streams.err.println(maybeThreadCount.errorOpt.get)
                (false, stateCache)

              } else {
                val userSpecifiedProperties =
                  userSpecifiedProperties0 ++ config.extraSystemProperties

                val threadCount = Some(maybeThreadCount.toOption.get)

                if (maybeScalaCompilerWorker.isInstanceOf[Result.Failure]) {
                  val err = maybeScalaCompilerWorker.errorOpt.get
                  streams.err.println(err)
                  (false, stateCache)
                } else {
                  val scalaCompilerWorker = maybeScalaCompilerWorker.get

                  val out = os.Path(OutFiles.out, WorkspaceRoot.workspaceRoot)
                  Using.resource(new TailManager(serverDir)) { tailManager =>
                    def runMillBootstrap(
                        enterKeyPressed: Boolean,
                        prevState: Option[RunnerState],
                        targetsAndParams: Seq[String],
                        streams: SystemStreams
                    ) = Server.withOutLock(
                      config.noBuildLock.value,
                      config.noWaitForBuildLock.value,
                      out,
                      targetsAndParams,
                      streams
                    ) {
                      Using.resource(getLogger(
                        streams,
                        config,
                        enableTicker =
                          if (bspMode) Some(false)
                          else config.ticker
                            .orElse(config.enableTicker)
                            .orElse(Option.when(config.disableTicker.value)(false)),
                        serverDir,
                        colored = colored,
                        colors = colors
                      )) { logger =>
                        // Enter key pressed, removing mill-selective-execution.json to
                        // ensure all tasks re-run even though no inputs may have changed
                        if (enterKeyPressed) os.remove(out / OutFiles.millSelectiveExecution)
                        mill.define.SystemStreams.withStreams(logger.streams) {
                          tailManager.withOutErr(logger.streams.out, logger.streams.err) {
                            new MillBuildBootstrap(
                              projectRoot = WorkspaceRoot.workspaceRoot,
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
                              scalaCompilerWorker = scalaCompilerWorker,
                              offline = config.offline.value
                            ).evaluate()
                          }
                        }
                      }
                    }

                    if (bspMode) {

                      runBspSession(
                        streams0,
                        streams,
                        prevRunnerState =>
                          runMillBootstrap(
                            false,
                            prevRunnerState,
                            Seq("version"),
                            streams
                          ).result
                      )

                      (true, RunnerState(None, Nil, None))
                    } else if (
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/idea") ||
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/")
                    ) {
                      val runnerState = runMillBootstrap(false, None, Seq("version"), streams)
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
                        watch = config.watch.value,
                        streams = streams,
                        setIdle = setIdle,
                        evaluate = (enterKeyPressed: Boolean, prevState: Option[RunnerState]) => {
                          adjustJvmProperties(userSpecifiedProperties, initialSystemProperties)
                          runMillBootstrap(
                            enterKeyPressed,
                            prevState,
                            config.leftoverArgs.value,
                            streams
                          )
                        },
                        colors = colors
                      )
                    }
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
      runMillBootstrap: Option[RunnerState] => RunnerState
  ): Result[BspServerResult] = {
    logStreams.err.println("Trying to load BSP server...")

    val wsRoot = WorkspaceRoot.workspaceRoot
    val logDir = wsRoot / OutFiles.out / "mill-bsp"
    val bspServerHandleRes = {
      os.makeDir.all(logDir)
      mill.bsp.worker.BspWorkerImpl.startBspServer(
        define.WorkspaceRoot.workspaceRoot,
        bspStreams,
        logDir,
        true
      )
    }

    logStreams.err.println("BSP server started")

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
          logStreams.err.println(s"BSP session returned with $runSessionRes")
        }

        // should make the lsp4j-managed BSP server exit
        bspStreams.in.close()

        bspRes.get
      } finally bspServerHandle.close()
    }

    logStreams.err.println(
      s"Exiting BSP runner loop. Stopping BSP server. Last result: $runSessionRes"
    )
    runSessionRes
  }

  private[runner] def parseThreadCount(
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
      serverDir: os.Path,
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
      terminfoPath = serverDir / ServerFiles.terminfo,
      currentTimeMillis = () => System.currentTimeMillis()
    )
  }

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
