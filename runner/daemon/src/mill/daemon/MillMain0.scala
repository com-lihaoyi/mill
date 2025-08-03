package mill.daemon

import ch.epfl.scala.bsp4j.BuildClient
import mill.api.daemon.internal.bsp.BspServerHandle
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}
import mill.api.{Logger, MillException, Result, SystemStreams}
import mill.bsp.BSP
import mill.client.lock.{DoubleLock, Lock}
import mill.constants.{DaemonFiles, OutFiles}
import mill.api.BuildCtx
import mill.internal.{
  Colors,
  JsonArrayLogger,
  MultiStream,
  PrefixLogger,
  PromptLogger,
  SimpleLogger,
  MillCliConfig
}
import mill.server.MillDaemonServer
import mill.util.BuildInfo
import mill.api
import mill.api.daemon.internal.bsp.BspServerResult

import java.io.{InputStream, PipedInputStream, PrintStream, PrintWriter, StringWriter}
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Using}

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
    case e =>
      val str = new StringWriter
      e.printStackTrace(new PrintWriter(str))
      err.println(str)
      throw e
  }

  private val outMemoryLock = Lock.memory()

  /**
   * We need a double lock because file system locks are not reentrant and blows up if you try to take them twice, while
   * memory locks just block until the lock is available.
   */
  def doubleLock(out: os.Path): DoubleLock = DoubleLock(
    outMemoryLock,
    Lock.file((out / OutFiles.millOutLock).toString)
  )

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

              checkMillVersionFromFile(BuildCtx.workspaceRoot, streams.err)

              val maybeThreadCount =
                parseThreadCount(config.threadCountRaw, Runtime.getRuntime.availableProcessors())

              // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
              val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty
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

                  val threadCount = maybeThreadCount.toOption.get

                  def createEc(): Option[ThreadPoolExecutor] =
                    if (threadCount == 1) None
                    else Some(mill.exec.ExecutionContexts.createExecutor(threadCount))

                  val out = os.Path(OutFiles.out, BuildCtx.workspaceRoot)
                  Using.resources(new TailManager(daemonDir), createEc()) { (tailManager, ec) =>
                    def runMillBootstrap(
                        enterKeyPressed: Boolean,
                        prevState: Option[RunnerState],
                        tasksAndParams: Seq[String],
                        streams: SystemStreams,
                        millActiveCommandMessage: String,
                        loggerOpt: Option[Logger] = None,
                        reporter: EvaluatorApi => Int => Option[CompileProblemReporter] =
                          _ => _ => None,
                        extraEnv: Seq[(String, String)] = Nil
                    ) = MillDaemonServer.withOutLock(
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
                        mill.api.SystemStreamsUtils.withStreams(logger.streams) {
                          mill.api.FilesystemCheckerEnabled.withValue(
                            !config.noFilesystemChecker.value
                          ) {
                            tailManager.withOutErr(logger.streams.out, logger.streams.err) {
                              new MillBuildBootstrap(
                                projectRoot = BuildCtx.workspaceRoot,
                                output = out,
                                // In BSP server, we want to evaluate as many tasks as possible,
                                // in order to give as many results as available in BSP responses
                                keepGoing = bspMode || config.keepGoing.value,
                                imports = config.imports,
                                env = env ++ extraEnv,
                                ec = ec,
                                tasksAndParams = tasksAndParams,
                                prevRunnerState = prevState.getOrElse(stateCache),
                                logger = logger,
                                needBuildFile = needBuildFile(config),
                                requestedMetaLevel = config.metaLevel,
                                config.allowPositional.value,
                                systemExit = systemExit,
                                streams0 = streams,
                                selectiveExecution = config.watch.value,
                                offline = config.offline.value,
                                reporter = reporter
                              ).evaluate()
                            }
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
                              .orElse(Option.when(config.tabComplete.value)(false))
                              .orElse(Option.when(config.disableTicker.value)(false)),
                            daemonDir,
                            colored = colored,
                            colors = colors,
                            out = out
                          )) { logger =>
                            proceed(logger)
                          }
                      }
                    }

                    if (config.tabComplete.value) {
                      val bootstrapped = runMillBootstrap(
                        enterKeyPressed = false,
                        Some(stateCache),
                        Seq(
                          "mill.tabcomplete.TabCompleteModule/complete"
                        ) ++ config.leftoverArgs.value,
                        streams,
                        "tab-completion"
                      )

                      (true, bootstrapped.result)
                    } else if (bspMode) {
                      val bspLogger = getBspLogger(streams, config)
                      var prevRunnerStateOpt = Option.empty[RunnerState]
                      val (bspServerHandle, buildClient) =
                        startBspServer(streams0, outLock, bspLogger)
                      var keepGoing = true
                      var errored = false
                      val initCommandLogger = new PrefixLogger(bspLogger, Seq("init"))
                      val watchLogger = new PrefixLogger(bspLogger, Seq("watch"))
                      while (keepGoing) {
                        val watchRes = runMillBootstrap(
                          false,
                          prevRunnerStateOpt,
                          Seq("version"),
                          initCommandLogger.streams,
                          "BSP:initialize",
                          loggerOpt = Some(initCommandLogger),
                          reporter = ev => {
                            val bspIdByModule = mill.bsp.worker.BspEvaluators(
                              BuildCtx.workspaceRoot,
                              Seq(ev),
                              _ => (),
                              Nil
                            ).bspIdByModule
                            mill.bsp.worker.Utils.getBspLoggedReporterPool(
                              "",
                              bspIdByModule,
                              buildClient
                            )
                          },
                          extraEnv = Seq("MILL_JVM_WORKER_REQUIRE_REPORTER" -> "true")
                        )

                        for (err <- watchRes.error)
                          bspLogger.streams.err.println(err)

                        prevRunnerStateOpt = Some(watchRes.result)

                        val sessionResultFuture = bspServerHandle.startSession(
                          watchRes.result.frames.flatMap(_.evaluator),
                          errored = watchRes.error.nonEmpty,
                          watched = watchRes.watched
                        )

                        val res =
                          if (config.bspWatch)
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
                          else {
                            watchLogger.info("Watching of build sources disabled")
                            Some {
                              try Success(Await.result(sessionResultFuture, Duration.Inf))
                              catch {
                                case NonFatal(ex) =>
                                  Failure(ex)
                              }
                            }
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

                      streams.err.println("Exiting BSP runner loop")

                      (!errored, RunnerState(None, Nil, None))
                    } else if (
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/idea") ||
                      config.leftoverArgs.value == Seq("mill.idea.GenIdea/") ||
                      config.leftoverArgs.value == Seq("mill.idea/")
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
                      if (config.watch.value)
                        os.remove(out / OutFiles.millSelectiveExecution)
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
   * Starts the BSP server
   *
   * @param bspStreams Streams to use for BSP JSONRPC communication with the BSP client
   */
  def startBspServer(
      bspStreams: SystemStreams,
      outLock: Lock,
      bspLogger: Logger
  ): (BspServerHandle, BuildClient) = {
    bspLogger.info("Trying to load BSP server...")

    val wsRoot = BuildCtx.workspaceRoot
    val logDir = wsRoot / OutFiles.out / "mill-bsp"
    os.makeDir.all(logDir)

    val bspServerHandleRes =
      mill.bsp.worker.BspWorkerImpl.startBspServer(
        api.BuildCtx.workspaceRoot,
        bspStreams,
        logDir,
        true,
        outLock,
        bspLogger,
        wsRoot / OutFiles.out
      ).get

    bspLogger.info("BSP server started")

    bspServerHandleRes
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
      colors: Colors,
      out: os.Path
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
      currentTimeMillis = () => System.currentTimeMillis(),
      chromeProfileLogger = new JsonArrayLogger.ChromeProfile(out / OutFiles.millChromeProfile)
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
    val tasksAndParams = config.leftoverArgs.value
    val whitelistMatch =
      tasksAndParams.nonEmpty && noBuildFileTaskWhitelist.exists(tasksAndParams.head == _)
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
