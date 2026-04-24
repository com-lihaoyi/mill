package mill.daemon

import mill.api.daemon.internal.bsp.BspServerHandle
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.api.daemon.internal.{LauncherOutFiles, LauncherLocking}
import mill.bsp.BSP
import mill.client.lock.{DoubleLock, Lock}
import mill.constants.{DaemonFiles, OutFolderMode}
import mill.constants.OutFiles.OutFiles
import mill.api.BuildCtx
  import mill.internal.{
    Colors,
    JsonArrayLogger,
    MillCliConfig,
    MultiStream,
    PrefixLogger,
    PromptLogger,
    BspLogger,
    LauncherLockingImpl
  }
import mill.server.Server
import mill.util.BuildInfo
import mill.api
import mill.api.daemon.internal.bsp.BspServerResult
import mill.api.daemon.internal.NonFatal

import java.io.{InputStream, PrintStream, PrintWriter, StringWriter}
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import sun.misc.Signal
import scala.jdk.CollectionConverters.*
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Using}

object MillMain0 {
  def handleMillException(err: PrintStream): PartialFunction[Throwable, Boolean] = {
    case e: MillException =>
      err.println(e.getMessage())
      false
    case e: InvocationTargetException
        if e.getCause != null && e.getCause.isInstanceOf[MillException] =>
      err.println(e.getCause.getMessage())
      false
    case e =>
      val str = new StringWriter
      e.printStackTrace(new PrintWriter(str))
      err.println(str)
      throw e
  }

  private val outMemoryLock = Lock.memory()

  /**
   * Creates both a [[DoubleLock]] (for non-daemon command-level locking) and
   * the underlying file lock (for cross-process daemon-lifetime exclusion).
   *
   * The DoubleLock combines an in-memory lock (for intra-process thread
   * serialization) with the file lock (for cross-process exclusion). The
   * daemon holds the file lock for its entire lifetime to exclude --no-daemon
   * processes, while the DoubleLock is only used in non-daemon and
   * config-mismatch code paths.
   */
  def outLocks(out: os.Path): (DoubleLock, Lock) = {
    val fileLock = Lock.file((out / OutFiles.millOutLock).toString)
    (DoubleLock(outMemoryLock, fileLock), fileLock)
  }

  private def withStreams[T](
      bspMode: Boolean,
      streams: SystemStreams
  )(thunk: SystemStreams => T): T =
    if (bspMode) {
      // In BSP mode, don't let anything other than the BSP server write to stdout and read from stdin

      val outDir = BuildCtx.workspaceRoot / os.RelPath(OutFiles.outFor(OutFolderMode.BSP))
      val outFileStream = os.write.outputStream(
        outDir / os.RelPath(OutFiles.bspOutLog),
        createFolders = true
      )
      val errFileStream = os.write.outputStream(
        outDir / os.RelPath(OutFiles.bspErrLog),
        createFolders = true
      )

      try {
        val streams0 = new SystemStreams(
          out = new MultiStream(streams.err, outFileStream),
          err = new MultiStream(streams.err, errFileStream),
          in = InputStream.nullInputStream()
        )
        mill.api.SystemStreamsUtils.withStreams(streams0) {
          thunk(streams0)
        }
      } finally {
        errFileStream.close()
        outFileStream.close()
      }
    } else
      mill.api.SystemStreamsUtils.withStreams(streams) {
        thunk(streams)
      }

  def main0(
      args: Array[String],
      sharedState: java.util.concurrent.atomic.AtomicReference[RunnerSharedState],
      mainInteractive: Boolean,
      streams0: SystemStreams,
      env: Map[String, String],
      launcherPid: Long,
      setIdle: Boolean => Unit,
      userSpecifiedProperties0: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Server.StopServer,
      daemonDir: os.Path,
      outLock: Lock,
      launcherSubprocessRunner: mill.api.daemon.LauncherSubprocess.Runner,
      serverToClientOpt: Option[mill.rpc.MillRpcChannel[mill.launcher.DaemonRpc.ServerToClient]],
      millRepositories: Seq[String]
  ): Boolean = mill.api.daemon.MillRepositories.withValue(millRepositories) {
    mill.api.daemon.LauncherSubprocess.withValue(launcherSubprocessRunner) {
      mill.api.daemon.internal.MillScalaParser.current.withValue(MillScalaParserImpl) {
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
              case f: Result.Failure =>
                streams.err.println(f.error)
                false

              case Result.Success(config) if config.help.value =>
                streams.out.println(MillCliConfig.longUsageText)
                true

              case Result.Success(config) if config.helpAdvanced.value =>
                streams.out.println(MillCliConfig.helpAdvancedUsageText)
                true

              case Result.Success(config) if config.showVersion.value =>
                val interestingProps = Seq(
                  "java.version",
                  "java.vendor",
                  "java.home",
                  "file.encoding",
                  "os.name",
                  "os.version",
                  "os.arch"
                )

                streams.out.println(
                  s"Mill Build Tool version ${BuildInfo.millVersion}\n" +
                    interestingProps.map(k => s"$k: ${System.getProperty(k, s"<unknown $k>")}")
                      .mkString("\n")
                )
                true

              case Result.Success(config) if config.noDaemonEnabled > 1 =>
                streams.err.println(
                  "Only one of -i/--interactive, --no-daemon or --no-server may be given"
                )
                false

              case Result.Success(config) if config.bsp.value && config.noDaemonEnabled > 0 =>
                streams.err.println(
                  "BSP mode runs via the Mill daemon; do not combine --bsp with -i/--interactive, --no-daemon or --no-server"
                )
                false

              case Result.Success(config) =>
                val noColorViaEnv = env.get("NO_COLOR").exists(_.nonEmpty)
                val forceColorViaEnv = env.get("FORCE_COLOR").exists(_.nonEmpty)
                val colored = config.color.getOrElse(
                  (mainInteractive || forceColorViaEnv) &&
                    !(noColorViaEnv || config.bsp.value)
                )
                val colors =
                  if (colored) mill.internal.Colors.Default else mill.internal.Colors.BlackWhite

                checkMillVersionFromFile(BuildCtx.workspaceRoot, streams.err)

                val maybeThreadCount =
                  parseThreadCount(config.threadCountRaw, Runtime.getRuntime.availableProcessors())

                // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
                val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty
                val outMode = if (bspMode) OutFolderMode.BSP else OutFolderMode.REGULAR
                val bspInstallModeJobCountOpt = {
                  def defaultJobCount =
                    maybeThreadCount.toOption.getOrElse(BSP.defaultJobCount)

                  val viaEmulatedExternalCommand = Option.when(
                    !config.bsp.value &&
                      (config.leftoverArgs.value.headOption.contains("mill.bsp.BSP/install") ||
                        config.leftoverArgs.value.headOption.contains("mill.bsp/install"))
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
                      case _ =>
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
                val enableTicker = config.ticker
                  .orElse(config.enableTicker)
                  .orElse(Option.when(
                    config.disableTicker.value || config.tabComplete.value || config.bsp.value
                  )(false))
                  .getOrElse(true)

                val success: Boolean = {
                  if (bspInstallModeJobCountOpt.isDefined) {
                    BSP.install(bspInstallModeJobCountOpt.get, config.debugLog.value, streams.err)
                    true
                  } else if (!bspMode && config.leftoverArgs.value.isEmpty) {
                    println(MillCliConfig.shortUsageText)
                    true
                  } else if (maybeThreadCount.errorOpt.isDefined) {
                    streams.err.println(maybeThreadCount.errorOpt.get)
                    false
                  } else {
                    val userSpecifiedProperties =
                      userSpecifiedProperties0 ++ config.extraSystemProperties

                    val threadCount = maybeThreadCount.toOption.get

                    def createEc(): Option[ThreadPoolExecutor] =
                      if (threadCount == 1) None
                      else Some(mill.exec.ExecutionContexts.createExecutor(threadCount))

                    val out = os.Path(OutFiles.outFor(outMode), BuildCtx.workspaceRoot)
                    Using.resources(new TailManager(daemonDir), createEc()) { (tailManager, ec) =>
                      def runMillBootstrap(
                          skipSelectiveExecution: Boolean,
                          prevState: Option[RunnerLauncherState],
                          tasksAndParams: Seq[String],
                          streams: SystemStreams,
                          millActiveCommandMessage: String,
                          loggerOpt: Option[Logger] = None,
                          reporter: EvaluatorApi => Int => Option[CompileProblemReporter] =
                            _ => _ => None,
                          extraEnv: Seq[(String, String)] = Nil,
                          metaLevelOverride: Option[Int] = None
                      ): RunnerLauncherState = {
                        val sessionOpt: Option[LauncherLockingImpl] =
                          Option.when(serverToClientOpt.nonEmpty) {
                            new LauncherLockingImpl(
                              out = out,
                              daemonDir = daemonDir,
                              activeCommandMessage = millActiveCommandMessage,
                              launcherPid = launcherPid,
                              waitingErr = streams.err,
                              noBuildLock = config.noBuildLock.value,
                              noWaitForBuildLock = config.noWaitForBuildLock.value
                            )
                          }

                        val (workspaceLocking, runArtifacts): (LauncherLocking, LauncherOutFiles) =
                          sessionOpt match {
                            case Some(s) => (s, s)
                            case None => (LauncherLocking.Noop, LauncherOutFiles.Noop)
                          }

                        def withLocking[T](block: => T): T = sessionOpt match {
                          case Some(session) =>
                            try {
                              setIdle(false)
                              block
                            } catch {
                              case e: Throwable =>
                                try session.close()
                                catch { case _: Throwable => }
                                throw e
                            }
                          case None =>
                            Server.withOutLock(
                              noBuildLock = config.noBuildLock.value,
                              noWaitForBuildLock = config.noWaitForBuildLock.value,
                              out = out,
                              daemonDir = daemonDir,
                              millActiveCommandMessage = millActiveCommandMessage,
                              streams = streams,
                              outLock = outLock,
                              setIdle = setIdle
                            ) {
                              setIdle(false)
                              block
                            }
                        }

                        val evaluated = withLocking {
                          def proceed(logger: Logger): RunnerLauncherState = {
                            // Enter key pressed, removing mill-selective-execution.json to
                            // ensure all tasks re-run even though no inputs may have changed
                            //
                            // Do this by removing the file rather than disabling selective execution,
                            // because we still want to generate the selective execution metadata json
                            // for subsequent runs that may use it
                            if (skipSelectiveExecution)
                              workspaceLocking.withSelectiveExecutionLock(
                                (out / OutFiles.millSelectiveExecution).toNIO
                              ) {
                                os.remove(out / OutFiles.millSelectiveExecution)
                              }
                            // Make this run's tail log + artifacts the ones `out/mill-*`
                            // symlinks point at, so concurrent launchers waiting on locks
                            // (and humans tailing the log) see the right file.
                            runArtifacts.publishArtifacts()
                            mill.api.SystemStreamsUtils.withStreams(logger.streams) {
                              mill.api.FilesystemCheckerEnabled.withValue(
                                !config.noFilesystemChecker.value
                              ) {
                                tailManager.withOutErr(logger.streams.out, logger.streams.err) {
                                  new MillBuildBootstrap(
                                    topLevelProjectRoot = BuildCtx.workspaceRoot,
                                    output = out,
                                    // In BSP server, we want to evaluate as many tasks as possible,
                                    // in order to give as many results as available in BSP responses
                                    keepGoing = bspMode || config.keepGoing.value,
                                    imports = config.imports,
                                    env = env ++ extraEnv,
                                    ec = ec,
                                    tasksAndParams = tasksAndParams,
                                    prevCommandState =
                                      prevState.getOrElse(RunnerLauncherState.empty),
                                    logger = logger,
                                    requestedMetaLevel = config.metaLevel.orElse(metaLevelOverride),
                                    allowPositionalCommandArgs = config.allowPositional.value,
                                    systemExit = systemExit,
                                    streams0 = streams,
                                    selectiveExecution = config.watch.value,
                                    offline = config.offline.value,
                                    useFileLocks = config.useFileLocks.value,
                                    workspaceLocking = workspaceLocking,
                                    runArtifacts = runArtifacts,
                                    sharedState = sharedState,
                                    reporter = reporter,
                                    enableTicker = enableTicker
                                  ).evaluate()
                                }
                              }
                            }
                          }

                          loggerOpt match {
                            case Some(logger) => proceed(logger)
                            case None =>
                              Using.resource(getLogger(
                                streams = streams,
                                config = config,
                                enableTicker = enableTicker,
                                colored = colored,
                                colors = colors,
                                out = out,
                                runArtifacts = runArtifacts,
                                serverToClientOpt = serverToClientOpt
                              )) { logger =>
                                proceed(logger)
                              }
                          }
                        }

                        sessionOpt.fold(evaluated)(evaluated.withCloseable)
                      }

                      if (config.tabComplete.value) {
                        Using.resource(
                          runMillBootstrap(
                            skipSelectiveExecution = false,
                            None,
                            Seq(
                              "mill.tabcomplete.TabCompleteModule/complete"
                            ) ++ config.leftoverArgs.value,
                            streams,
                            "tab-completion"
                          )
                        )(_ => true)
                      } else if (bspMode) {
                        // Can happen if a concurrent BSP server starts and shuts us down.
                        // We log in the console what happened just in case, so that users know why we exit.
                        // This is also used in the tests.
                        Signal.handle(
                          new Signal("TERM"),
                          _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
                        )

                        val bspLogger = getBspLogger(streams, config)
                        var prevRunnerLauncherStateOpt = Option.empty[RunnerLauncherState]
                        val (bspServerHandle, buildClient) = startBspServer(
                          streams0,
                          bspLogger,
                          noWaitForBspLock = config.noWaitForBspLock.value,
                          killOther = !config.bspNoKillOther.value
                        )
                        var keepGoing = true
                        var errored = false
                        val initCommandLogger = new PrefixLogger(bspLogger, Seq("init"))
                        val watchLogger = new PrefixLogger(bspLogger, Seq("watch"))
                        while (keepGoing) {
                          val watchRes = runMillBootstrap(
                            skipSelectiveExecution = false,
                            prevState = prevRunnerLauncherStateOpt,
                            tasksAndParams = Seq("resolve", "_"),
                            streams = initCommandLogger.streams,
                            millActiveCommandMessage = "BSP:initialize",
                            loggerOpt = Some(initCommandLogger),
                            reporter = ev => {
                              IdeWorkerSupport.bspReporterPool(
                                workspaceDir = BuildCtx.workspaceRoot,
                                evaluators = Seq(ev),
                                buildClient = buildClient
                              )
                            }
                          )

                          for (err <- watchRes.errorOpt) bspLogger.streams.err.println(err)

                          // The previous loop iteration called resetSession before continuing, so
                          // the previous launch state belongs to an inactive BSP session.
                          prevRunnerLauncherStateOpt.foreach(_.close())
                          prevRunnerLauncherStateOpt = Some(watchRes)

                          val sessionResultFuture = bspServerHandle.startSession(
                            evaluators = watchRes.allEvaluators,
                            errored = watchRes.errorOpt.nonEmpty,
                            watched = watchRes.watched
                          )

                          def waitWithoutWatching() = {
                            Some {
                              try Success(Await.result(sessionResultFuture, Duration.Inf))
                              catch {
                                case NonFatal(ex) =>
                                  Failure(ex)
                              }
                            }
                          }

                          val res =
                            if (config.bspWatch) {
                              try {
                                Watching.watchAndWait(
                                  watchRes.watched,
                                  Watching.WatchArgs(
                                    setIdle = setIdle,
                                    colors = mill.internal.Colors.BlackWhite,
                                    useNotify = config.watchViaFsNotify,
                                    daemonDir = daemonDir
                                  ),
                                  () => sessionResultFuture.value,
                                  "",
                                  watchLogger.info(_)
                                )
                              } catch {
                                case e: Exception =>
                                  val sw = new java.io.StringWriter
                                  e.printStackTrace(new java.io.PrintWriter(sw))
                                  watchLogger.info(
                                    "Watching of build sources failed:" + e + "\n" + sw
                                  )
                                  waitWithoutWatching()
                              }
                            } else {
                              watchLogger.info("Watching of build sources disabled")
                              waitWithoutWatching()
                            }

                          // Suspend any BSP request until the next call to startSession
                          // (that is, until we've attempted to re-compile the build)
                          bspServerHandle.resetSession()

                          res match {
                            case None =>
                            // Some watched meta-build files changed
                            case Some(Failure(ex)) =>
                              streams.err.println("BSP server threw an exception, exiting")
                              ex.printStackTrace(streams.err)
                              errored = true
                              keepGoing = false
                            case Some(Success(BspServerResult.ReloadWorkspace)) =>
                            // reload asked by client
                            case Some(Success(BspServerResult.Shutdown)) =>
                              streams.err.println("BSP shutdown asked by client, exiting")
                              // shutdown asked by client
                              keepGoing = false
                              // should make the lsp4j-managed BSP server exit
                              streams.in.close()
                          }
                        }

                        prevRunnerLauncherStateOpt.foreach(_.close())
                        streams.err.println("Exiting BSP runner loop")
                        !errored
                      } else if (
                        config.leftoverArgs.value == Seq("mill.idea.GenIdea/idea") ||
                        config.leftoverArgs.value == Seq("mill.idea.GenIdea/") ||
                        config.leftoverArgs.value == Seq("mill.idea/")
                      ) {
                        Using.resource(
                          runMillBootstrap(
                            false,
                            None,
                            Seq("resolve", "_"),
                            streams,
                            "BSP:initialize"
                          )
                        ) { runnerState =>
                          IdeWorkerSupport.runIdeaGeneration(
                            runnerState.allEvaluators
                          )
                        }
                        true
                      } else if (
                        config.leftoverArgs.value == Seq("mill.eclipse.GenEclipse/eclipse") ||
                        config.leftoverArgs.value == Seq("mill.eclipse.GenEclipse/") ||
                        config.leftoverArgs.value == Seq("mill.eclipse/")
                      ) {
                        Using.resource(
                          runMillBootstrap(
                            false,
                            None,
                            Seq("resolve", "_"),
                            streams,
                            "BSP:initialize"
                          )
                        ) { runnerState =>
                          new mill.eclipse.GenEclipseImpl(runnerState.allEvaluators)
                            .run()
                        }
                        true
                      } else {
                        val (watchSuccess, watchState) = Watching.watchLoop(
                          ringBell = config.ringBell.value,
                          watch = Option.when(config.watch.value)(Watching.WatchArgs(
                            setIdle = setIdle,
                            colors,
                            useNotify = config.watchViaFsNotify,
                            daemonDir = daemonDir
                          )),
                          streams = streams,
                          evaluate =
                            (
                                skipSelectiveExecution: Boolean,
                                prevState: Option[RunnerLauncherState]
                            ) => {
                              adjustJvmProperties(userSpecifiedProperties, initialSystemProperties)
                              runMillBootstrap(
                                skipSelectiveExecution = skipSelectiveExecution,
                                prevState = prevState,
                                tasksAndParams = config.leftoverArgs.value,
                                streams = streams,
                                millActiveCommandMessage = config.leftoverArgs.value.mkString(" ")
                              )
                            }
                        )
                        try watchSuccess
                        finally watchState.close()
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
                success

            }
          }
        }
      }
    }
  }

  /**
   * Starts the BSP server
   *
   * @param bspStreams Streams to use for BSP JSONRPC communication with the BSP client
   */
  def startBspServer(
      bspStreams: SystemStreams,
      bspLogger: Logger,
      noWaitForBspLock: Boolean,
      killOther: Boolean
  ): (BspServerHandle, IdeWorkerSupport.BspBuildClient) = {
    bspLogger.info("Trying to load BSP server...")

    val wsRoot = BuildCtx.workspaceRoot
    val outFolder = wsRoot / os.RelPath(OutFiles.outFor(OutFolderMode.BSP))
    val logDir = outFolder / "mill-bsp"
    os.makeDir.all(logDir)

    val bspServerHandleRes =
      IdeWorkerSupport.startBspServer(
        topLevelBuildRoot = api.BuildCtx.workspaceRoot,
        streams = bspStreams,
        logDir = logDir,
        canReload = true,
        baseLogger = bspLogger,
        out = outFolder,
        noWaitForBspLock = noWaitForBspLock,
        killOther = killOther
      )

    bspLogger.info("BSP server started")

    bspServerHandleRes
  }

  def parseThreadCount(
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
      enableTicker: Boolean,
      colored: Boolean,
      colors: Colors,
      out: os.Path,
      runArtifacts: LauncherOutFiles,
      serverToClientOpt: Option[mill.rpc.MillRpcChannel[mill.launcher.DaemonRpc.ServerToClient]]
  ): Logger & AutoCloseable = {
    val cmdTitle = sys.props.get("mill.main.cli")
      .map { prog =>
        val path = sys.env.get("PATH").map(_.split("[:]").toIndexedSeq).getOrElse(Seq())
        // shorten the cmd path, when it is on the PATH
        path.collectFirst {
          case prefix if prog.startsWith(s"$prefix/") => prog.drop(prefix.length + 1)
        }
          // or use as-is
          .getOrElse(prog)
      }
      // Fallback
      .getOrElse("mill")

    // Console log file for monitoring progress when another process is waiting.
    // Noop routing returns a workspace-root-relative path (os.pwd fallback); in
    // non-daemon mode we want the actual out/ location.
    val consoleLogPath: os.Path = runArtifacts match {
      case LauncherOutFiles.Noop => out / DaemonFiles.millConsoleTail
      case _ => os.Path(runArtifacts.consoleTail)
    }
    val consoleLogStream = new RotatingConsoleLogOutputStream(consoleLogPath)

    val teeStreams = new SystemStreams(
      new MultiStream(streams.out, consoleLogStream),
      new MultiStream(streams.err, consoleLogStream),
      streams.in
    )

    // Create terminal dimensions callback - uses RPC if available, otherwise gets directly
    val terminalDimsCallback: () => Option[(Option[Int], Option[Int])] = serverToClientOpt match {
      case Some(serverToClient) => () =>
          try {
            val result = serverToClient(mill.launcher.DaemonRpc.ServerToClient.GetTerminalDims())
            Some((result.width, result.height))
          } catch {
            case _: Exception => None
          }
      case None => () =>
          val result = mill.launcher.MillProcessLauncher.getTerminalDims()
          Some((result.width, result.height))
    }

    val promptLogger = new PromptLogger(
      colored = colored,
      enableTicker = enableTicker,
      infoColor = colors.info,
      warnColor = colors.warn,
      errorColor = colors.error,
      successColor = colors.success,
      highlightColor = colors.highlight,
      systemStreams0 = teeStreams,
      debugEnabled = config.debugLog.value,
      titleText = (cmdTitle +: config.leftoverArgs.value).mkString(" "),
      terminalDimsCallback = terminalDimsCallback,
      currentTimeMillis = () => System.currentTimeMillis(),
      chromeProfileLogger = new JsonArrayLogger.ChromeProfile(
        os.Path(runArtifacts.artifactPath((out / OutFiles.millChromeProfile).toNIO))
      )
    )
    new PrefixLogger(promptLogger, Nil) with AutoCloseable {
      override def close(): Unit = {
        promptLogger.close()
        consoleLogStream.close()
      }
    }
  }

  def getBspLogger(
      streams: SystemStreams,
      config: MillCliConfig
  ): Logger = {
    val outFolder = BuildCtx.workspaceRoot / os.RelPath(OutFiles.outFor(OutFolderMode.BSP))
    val chromeProfileLogger = new JsonArrayLogger.ChromeProfile(
      outFolder / OutFiles.millChromeProfile
    )
    new PrefixLogger(
      new BspLogger(streams, Seq("bsp"), debugEnabled = config.debugLog.value, chromeProfileLogger),
      Nil
    )
  }

  /**
   * Determine, whether we need a `build.mill` or not.
   */

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
      if (BuildInfo.millVersion != version.stripSuffix("-native").stripSuffix("-jvm")) {
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

  private implicit lazy val threadPoolExecutorOptionReleasable
      : Using.Releasable[Option[ThreadPoolExecutor]] =
    new Using.Releasable[Option[ThreadPoolExecutor]] {
      def release(resource: Option[ThreadPoolExecutor]): Unit =
        for (t <- resource) {
          t.shutdown()
          t.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)
        }
    }
}
