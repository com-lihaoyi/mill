package mill.runner

import mill.api

import java.io.{PipedInputStream, PrintStream}
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Properties
import mill.api.{Logger, MillException, Result, SystemStreams, WorkspaceRoot, internal}
import mill.bsp.{BspContext, BspServerResult}
import mill.constants.{OutFiles, ServerFiles, Util}
import mill.client.lock.Lock
import mill.main.BuildInfo
import mill.runner.worker.ScalaCompilerWorker
import mill.internal.{Colors, PromptLogger}

import java.lang.reflect.InvocationTargetException
import scala.util.control.NonFatal
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

  def main(args: Array[String]): Unit = SystemStreams.withTopLevelSystemStreamProxy {
    val initialSystemStreams = SystemStreams.original
    // setup streams
    val (runnerStreams, cleanupStreams, bspLog) =
      if (args.headOption == Option("--bsp")) {
        // In BSP mode, we use System.in/out for protocol communication
        // and all Mill output (stdout and stderr) goes to a dedicated file
        val stderrFile = WorkspaceRoot.workspaceRoot / ".bsp/mill-bsp.stderr"
        os.makeDir.all(stderrFile / os.up)
        val errFile =
          new PrintStream(Files.newOutputStream(stderrFile.toNIO, StandardOpenOption.APPEND))
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

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val processId = mill.main.server.Server.computeProcessId()
    val out = os.Path(OutFiles.out, WorkspaceRoot.workspaceRoot)
    mill.main.server.Server.watchProcessIdFile(
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
          streams0 = runnerStreams,
          bspLog = bspLog,
          env = System.getenv().asScala.toMap,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          systemExit = i => sys.exit(i),
          serverDir = os.Path(args.head)
        )
      catch handleMillException(runnerStreams.err, ())
      finally {
        cleanupStreams.foreach(_.close())
      }
    System.exit(if (result) 0 else 1)
  }

  lazy val maybeScalaCompilerWorker = ScalaCompilerWorker.bootstrapWorker()

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams0: SystemStreams,
      bspLog: Option[PrintStream],
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties0: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing,
      serverDir: os.Path
  ): (Boolean, RunnerState) = {
    val streams = streams0
    SystemStreams.withStreams(streams) {
      os.SubProcess.env.withValue(env) {
        MillCliConfigParser.parse(args) match {
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

            // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
            val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty
            val maybeThreadCount =
              parseThreadCount(config.threadCountRaw, Runtime.getRuntime.availableProcessors())

            val (success, nextStateCache) = {
              if (config.repl.value) {
                streams.err.println("The --repl mode is no longer supported.")
                (false, stateCache)

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
                  val bspContext =
                    if (bspMode) Some(new BspContext(streams, bspLog, config.home)) else None

                  val bspCmd = "mill.bsp.BSP/startSession"
                  val targetsAndParams =
                    bspContext
                      .map(_ => Seq(bspCmd))
                      .getOrElse(config.leftoverArgs.value.toList)

                  val out = os.Path(OutFiles.out, WorkspaceRoot.workspaceRoot)

                  var repeatForBsp = true
                  var loopRes: (Boolean, RunnerState) = (false, RunnerState.empty)
                  while (repeatForBsp) {
                    repeatForBsp = false

                    Using.resource(new TailManager(serverDir)) { tailManager =>
                      if (config.watch.value) {
                        // When starting a --watch, clear the `mill-selective-execution.json`
                        // file, so that the first run always selects everything and only
                        // subsequent re-runs are selective depending on what changed.
                        os.remove(out / OutFiles.millSelectiveExecution)
                      }
                      val (isSuccess, evalStateOpt) = Watching.watchLoop(
                        ringBell = config.ringBell.value,
                        watch = config.watch.value,
                        streams = streams,
                        setIdle = setIdle,
                        evaluate = (enterKeyPressed: Boolean, prevState: Option[RunnerState]) => {
                          adjustJvmProperties(userSpecifiedProperties, initialSystemProperties)

                          withOutLock(
                            config.noBuildLock.value || bspContext.isDefined,
                            config.noWaitForBuildLock.value,
                            out,
                            targetsAndParams,
                            streams
                          ) {
                            Using.resource(getLogger(
                              streams,
                              config,
                              enableTicker =
                                config.ticker
                                  .orElse(config.enableTicker)
                                  .orElse(Option.when(config.disableTicker.value)(false)),
                              serverDir,
                              colored = colored,
                              colors = colors
                            )) { logger =>
                              // Enter key pressed, removing mill-selective-execution.json to
                              // ensure all tasks re-run even though no inputs may have changed
                              if (enterKeyPressed) os.remove(out / OutFiles.millSelectiveExecution)
                              SystemStreams.withStreams(logger.streams) {
                                tailManager.withOutErr(logger.streams.out, logger.streams.err) {
                                  new MillBuildBootstrap(
                                    projectRoot = WorkspaceRoot.workspaceRoot,
                                    output = out,
                                    home = config.home,
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
                                    streams0 = streams0,
                                    selectiveExecution = config.watch.value,
                                    scalaCompilerWorker = scalaCompilerWorker
                                  ).evaluate()
                                }
                              }
                            }
                          }
                        },
                        colors = colors
                      )
                      bspContext.foreach { ctx =>
                        repeatForBsp =
                          BspContext.bspServerHandle.lastResult == Some(
                            BspServerResult.ReloadWorkspace
                          )
                        streams.err.println(
                          s"`$bspCmd` returned with ${BspContext.bspServerHandle.lastResult}"
                        )
                      }

                      loopRes = (isSuccess, evalStateOpt)
                    }
                  } // while repeatForBsp
                  bspContext.foreach { ctx =>
                    streams.err.println(
                      s"Exiting BSP runner loop. Stopping BSP server. Last result: ${BspContext.bspServerHandle.lastResult}"
                    )
                    BspContext.bspServerHandle.stop()
                  }

                  // return with evaluation result
                  loopRes
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

  def checkMillVersionFromFile(projectDir: os.Path, stderr: PrintStream): Unit = {
    Seq(
      projectDir / ".config/mill-version",
      projectDir / ".mill-version"
    ).collectFirst {
      case f if os.exists(f) =>
        (f, os.read.lines(f).find(l => l.trim().nonEmpty).get)
    }.foreach { case (file, version) =>
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

  def withOutLock[T](
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      out: os.Path,
      targetsAndParams: Seq[String],
      streams: SystemStreams
  )(t: => T): T = {
    if (noBuildLock) t
    else {
      val outLock = Lock.file((out / OutFiles.millLock).toString)

      def activeTaskString =
        try {
          os.read(out / OutFiles.millActiveCommand)
        } catch {
          case e => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"

      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked()) tryLocked
        else if (noWaitForBuildLock) {
          throw new Exception(s"$activeTaskPrefix failing")
        } else {

          streams.err.println(
            s"$activeTaskPrefix waiting for it to be done..."
          )
          outLock.lock()
        }
      } { _ =>
        os.write.over(out / OutFiles.millActiveCommand, targetsAndParams.mkString(" "))
        try t
        finally os.remove.all(out / OutFiles.millActiveCommand)
      }
    }
  }

}
