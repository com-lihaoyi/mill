package mill.daemon

import mill.api.daemon.internal.bsp.{BspBootstrapBridge, BspServerHandle}
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.bsp.BSP
import mill.client.lock.Lock
import mill.constants.OutFolderMode
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
  LauncherLockingImpl,
  LauncherLockRegistry,
  LauncherOutFilesImpl,
  LauncherOutFilesState,
  OutputDirectoryLayout
}
import mill.server.Server
import mill.util.BuildInfo
import mill.api

import java.io.{InputStream, PrintStream, PrintWriter, StringWriter}
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.Using

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

  /**
   * Process-level lock used to coordinate access to the output folder between
   * concurrent Mill processes.
   */
  def outFileLock(out: os.Path): Lock =
    Lock.file((out / OutFiles.millOutLock).toString)

  private[daemon] def useInProcessLauncherResources(
      hasDaemonClient: Boolean,
      bspMode: Boolean
  ): Boolean =
    hasDaemonClient || bspMode

  private def withStreams[T](
      bspMode: Boolean,
      env: Map[String, String],
      streams: SystemStreams
  )(thunk: SystemStreams => T): T =
    if (bspMode) {
      // In BSP mode, don't let anything other than the BSP server write to stdout and read from stdin

      val outDir = BuildCtx.workspaceRoot / os.RelPath(
        OutputDirectoryLayout.outDir(OutFolderMode.BSP, BuildCtx.workspaceRoot, env)
      )
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
      lockRegistry: LauncherLockRegistry,
      outFilesState: LauncherOutFilesState,
      mainInteractive: Boolean,
      streams0: SystemStreams,
      env: Map[String, String],
      launcherPid: Long,
      setIdle: Boolean => Unit,
      setRunningCommand: Option[String] => Unit,
      userSpecifiedProperties0: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Server.StopServer,
      daemonDir: os.Path,
      sharedOutLockManager: SharedOutLockManager,
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
          withStreams(bspMode, env, streams0) { streams =>
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

                    val out = os.Path(
                      OutputDirectoryLayout.outDir(outMode, BuildCtx.workspaceRoot, env),
                      BuildCtx.workspaceRoot
                    )
                    // Refcount the shared `setIdle` flag so it flips only
                    // on aggregate 0↔N transitions across concurrent BSP requests.
                    val activeRequests = new java.util.concurrent.atomic.AtomicInteger(0)
                    def beginActive(): Unit =
                      if (activeRequests.getAndIncrement() == 0) setIdle(false)
                    def endActive(): Unit =
                      if (activeRequests.decrementAndGet() == 0) setIdle(true)
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
                          // Depth-keyed meta-build compile reporter (BSP-only).
                          metaBuildReporter: Int => Option[CompileProblemReporter] =
                            _ => None,
                          extraEnv: Seq[(String, String)] = Nil,
                          metaLevelOverride: Option[Int] = None,
                          useBspRequestLogger: Boolean = false
                      ): RunnerLauncherState = {
                        def acquireOutFileLease(
                            markIdleWhileWaiting: Boolean
                        ): AutoCloseable = {
                          val underlying = sharedOutLockManager.lease(
                            noBuildLock = config.noBuildLock.value,
                            noWaitForBuildLock = config.noWaitForBuildLock.value,
                            waitingErr = streams.err
                          )
                          if (!markIdleWhileWaiting) underlying
                          else {
                            beginActive()
                            val released = new java.util.concurrent.atomic.AtomicBoolean(false)
                            new AutoCloseable {
                              override def close(): Unit =
                                if (released.compareAndSet(false, true)) {
                                  try underlying.close()
                                  finally endActive()
                                }
                            }
                          }
                        }

                        def createLauncherResources()
                            : (LauncherLocking, LauncherOutFiles, AutoCloseable) = {
                          val fileLockLease = acquireOutFileLease(markIdleWhileWaiting = true)
                          try {
                            if (
                              useInProcessLauncherResources(
                                hasDaemonClient = serverToClientOpt.nonEmpty,
                                bspMode = bspMode
                              )
                            ) {
                              val runId = outFilesState.nextRunId()
                              val locking = new LauncherLockingImpl(
                                activeCommandMessage = millActiveCommandMessage,
                                launcherPid = launcherPid,
                                noBuildLock = config.noBuildLock.value,
                                noWaitForBuildLock = config.noWaitForBuildLock.value,
                                lockRegistry = lockRegistry,
                                runId = runId
                              )
                              val artifacts = new LauncherOutFilesImpl(
                                out = out,
                                activeCommandMessage = millActiveCommandMessage,
                                launcherPid = launcherPid,
                                outFilesState = outFilesState,
                                runId = runId
                              )
                              (locking, artifacts, fileLockLease)
                            } else (
                              LauncherLocking.Noop,
                              LauncherOutFiles.noop(out.toNIO),
                              fileLockLease
                            )
                          } catch {
                            case e: Throwable =>
                              try fileLockLease.close()
                              catch { case _: Throwable => () }
                              throw e
                          }
                        }

                        def withSessionLogger[T](
                            runArtifacts: LauncherOutFiles
                        )(
                            body: Logger => T
                        ): T =
                          loggerOpt match {
                            case Some(logger) => body(logger)
                            case None =>
                              val loggerResource =
                                if (useBspRequestLogger)
                                  getBspLogger(
                                    streams = streams,
                                    config = config,
                                    chromeProfilePath = os.Path(runArtifacts.chromeProfile),
                                    consoleLogPathOpt = Some(os.Path(runArtifacts.consoleTail))
                                  )
                                else
                                  getLogger(
                                    streams = streams,
                                    config = config,
                                    enableTicker = enableTicker,
                                    colored = colored,
                                    colors = colors,
                                    runArtifacts = runArtifacts,
                                    serverToClientOpt = serverToClientOpt
                                  )
                              Using.resource(loggerResource)(body)
                          }

                        val (workspaceLocking, runArtifacts, fileLockLease) =
                          createLauncherResources()
                        try {
                          val state = withSessionLogger(runArtifacts) { logger =>
                            // Enter key pressed: remove mill-selective-execution.json to
                            // ensure all tasks re-run even if no inputs changed.
                            //
                            // Do this by removing the file rather than disabling selective
                            // execution entirely, because we still want to regenerate the
                            // metadata for subsequent runs.
                            if (skipSelectiveExecution)
                              os.remove(out / OutFiles.millSelectiveExecution)
                            runArtifacts.publishLiveArtifacts()
                            try mill.api.SystemStreamsUtils.withStreams(logger.streams) {
                                mill.api.FilesystemCheckerEnabled.withValue(
                                  !config.noFilesystemChecker.value
                                ) {
                                  tailManager.withOutErr(
                                    logger.streams.out,
                                    logger.streams.err
                                  ) {
                                    new MillBuildBootstrap(
                                      topLevelProjectRoot = BuildCtx.workspaceRoot,
                                      output = out,
                                      // In BSP server mode, evaluate as many tasks as possible
                                      // so BSP responses can expose as much information as available.
                                      keepGoing = bspMode || config.keepGoing.value,
                                      imports = config.imports,
                                      env = env ++ extraEnv,
                                      ec = ec,
                                      tasksAndParams = tasksAndParams,
                                      prevCommandState =
                                        prevState.getOrElse(RunnerLauncherState.empty),
                                      logger = logger,
                                      requestedMetaLevel =
                                        config.metaLevel.orElse(metaLevelOverride),
                                      allowPositionalCommandArgs =
                                        config.allowPositional.value,
                                      systemExit = systemExit,
                                      streams0 = streams,
                                      selectiveExecution = config.watch.value,
                                      offline = config.offline.value,
                                      useFileLocks = config.useFileLocks.value,
                                      runArtifacts = runArtifacts,
                                      metaBuild = new MetaBuildAccess(
                                        ref = sharedState,
                                        workspaceLocking = workspaceLocking
                                      ),
                                      reporter = reporter,
                                      metaBuildReporter = metaBuildReporter,
                                      enableTicker = enableTicker
                                    ).evaluate()
                                  }
                                }
                              }
                            // Publish in-progress, then republish after
                            // logger close so the Windows copy fallback
                            // for JSON-array logs sees the trailing `]`.
                            finally runArtifacts.publishArtifacts()
                          }
                          runArtifacts.publishArtifacts()
                          state.withResources(workspaceLocking, runArtifacts, fileLockLease)
                        } catch {
                          case e: Throwable =>
                            try workspaceLocking.close()
                            catch { case _: Throwable => () }
                            try runArtifacts.close()
                            catch { case _: Throwable => () }
                            try fileLockLease.close()
                            catch { case _: Throwable => () }
                            throw e
                        }
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
                        Using.resource(getBspLogger(
                          streams = streams,
                          config = config,
                          chromeProfilePath =
                            out / os.RelPath("mill-bsp") / OutFiles.millChromeProfile
                        )) { bspLogger =>
                          // Can happen if a concurrent BSP server starts and shuts us down.
                          // We log in the console what happened just in case, so that users know why we exit.
                          // This is also used in the tests.
                          sun.misc.Signal.handle(
                            new sun.misc.Signal("TERM"),
                            _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
                          )

                          // Each BSP request bootstraps a fresh `RunnerLauncherState`;
                          // meta-build frames are shared via `RunnerSharedState` under
                          // the meta-build locks. `metaReporter` lets the BSP worker
                          // route meta-build compile diagnostics to its `BuildClient`.
                          val bootstrapBridge: BspBootstrapBridge =
                            new BspBootstrapBridge {
                              def apply[T](
                                  activeCommandMessage: String,
                                  metaReporter: Int => Option[CompileProblemReporter],
                                  body: (
                                      Seq[EvaluatorApi],
                                      Seq[mill.api.daemon.Watchable],
                                      Option[String]
                                  ) => T
                              ): T =
                                Using.resource(
                                  runMillBootstrap(
                                    skipSelectiveExecution = false,
                                    prevState = None,
                                    tasksAndParams = Seq("resolve", "_"),
                                    streams = streams,
                                    millActiveCommandMessage = activeCommandMessage,
                                    metaBuildReporter = metaReporter,
                                    useBspRequestLogger = true
                                  )
                                ) { runnerState =>
                                  body(
                                    runnerState.allEvaluators,
                                    runnerState.watched,
                                    runnerState.errorOpt
                                  )
                                }
                            }

                          val bspServerHandle = startBspServer(
                            streams0,
                            bspLogger,
                            bspWatch = config.bspWatch,
                            bootstrapBridge = bootstrapBridge,
                            env = env
                          )

                          val shutdownResult =
                            try scala.util.Success(scala.concurrent.Await.result(
                                bspServerHandle.shutdownFuture,
                                scala.concurrent.duration.Duration.Inf
                              ))
                            catch {
                              case mill.api.daemon.internal.NonFatal(ex) =>
                                scala.util.Failure(ex)
                            }

                          val errored = shutdownResult match {
                            case scala.util.Failure(ex) =>
                              streams.err.println("BSP server threw an exception, exiting")
                              ex.printStackTrace(streams.err)
                              true
                            case scala.util.Success(
                                  mill.api.daemon.internal.bsp.BspServerResult.Shutdown
                                ) =>
                              streams.err.println("BSP shutdown asked by client, exiting")
                              streams.in.close()
                              false
                            case scala.util.Success(
                                  mill.api.daemon.internal.bsp.BspServerResult.ReloadWorkspace
                                ) =>
                              streams.err.println("BSP reload asked by client, exiting")
                              streams.in.close()
                              false
                          }
                          bspServerHandle.close()
                          streams.err.println("Exiting BSP runner loop")
                          !errored
                        }
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
                          runnerState.errorOpt match {
                            case Some(err) =>
                              streams.err.println(err)
                              false
                            case None =>
                              IdeWorkerSupport.runIdeaGeneration(
                                runnerState.allEvaluators
                              )
                              true
                          }
                        }
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
                          runnerState.errorOpt match {
                            case Some(err) =>
                              streams.err.println(err)
                              false
                            case None =>
                              new mill.eclipse.GenEclipseImpl(runnerState.allEvaluators)
                                .run()
                              true
                          }
                        }
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
                              val activeCommandMessage = config.leftoverArgs.value.mkString(" ")
                              setRunningCommand(Some(activeCommandMessage))
                              runMillBootstrap(
                                skipSelectiveExecution = skipSelectiveExecution,
                                prevState = prevState,
                                tasksAndParams = config.leftoverArgs.value,
                                streams = streams,
                                millActiveCommandMessage = activeCommandMessage
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
      bspWatch: Boolean,
      bootstrapBridge: BspBootstrapBridge,
      env: Map[String, String]
  ): BspServerHandle = {
    bspLogger.info("Trying to load BSP server...")

    val wsRoot = BuildCtx.workspaceRoot
    val outFolder = wsRoot / os.RelPath(
      OutputDirectoryLayout.outDir(OutFolderMode.BSP, wsRoot, env)
    )
    val logDir = outFolder / "mill-bsp"
    os.makeDir.all(logDir)

    val bspServerHandleRes =
      IdeWorkerSupport.startBspServer(
        topLevelBuildRoot = api.BuildCtx.workspaceRoot,
        streams = bspStreams,
        logDir = logDir,
        canReload = true,
        baseLogger = bspLogger,
        bspWatch = bspWatch,
        bootstrapBridge = bootstrapBridge
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

    // Console log file for monitoring progress when another process is waiting
    val consoleLogPath = os.Path(runArtifacts.consoleTail)
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
      chromeProfileLogger = new JsonArrayLogger.ChromeProfile(os.Path(runArtifacts.chromeProfile))
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
      config: MillCliConfig,
      chromeProfilePath: os.Path,
      consoleLogPathOpt: Option[os.Path] = None
  ): Logger & AutoCloseable = {
    val consoleLogStreamOpt = consoleLogPathOpt.map(new RotatingConsoleLogOutputStream(_))
    val loggerStreams = consoleLogStreamOpt match {
      case Some(consoleLogStream) =>
        new SystemStreams(
          new MultiStream(streams.out, consoleLogStream),
          new MultiStream(streams.err, consoleLogStream),
          streams.in
        )
      case None => streams
    }
    val chromeProfileLogger = new JsonArrayLogger.ChromeProfile(chromeProfilePath)
    new PrefixLogger(
      new BspLogger(
        loggerStreams,
        Seq("bsp"),
        debugEnabled = config.debugLog.value,
        chromeProfileLogger
      ),
      Nil
    ) with AutoCloseable {
      override def close(): Unit = {
        chromeProfileLogger.close()
        consoleLogStreamOpt.foreach(_.close())
      }
    }
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

  /**
   * Best-effort reset of `System.getProperties` to the daemon's initial
   * properties plus the launcher's user-specified `-D` overrides.
   *
   * Concurrency caveat: `System.getProperties` is process-global, so this
   * method is inherently racy when multiple launchers run in the same daemon.
   * Launcher A's call here can clobber launcher B's `-D` overrides mid-run,
   * and any task code that mutates `sys.props` from one launcher will be
   * visible to the others. There is no fix at this layer — the JVM has no
   * per-thread/per-evaluation system-property scope. Build authors who need
   * isolated configuration should pass values through task inputs/env vars
   * rather than via `-D` system properties.
   */
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
