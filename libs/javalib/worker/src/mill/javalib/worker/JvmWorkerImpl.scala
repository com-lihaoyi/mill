package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.constants.DaemonFiles
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.*
import mill.javalib.internal.{JvmWorkerArgs, RpcCompileProblemReporterMessage}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.{CachedFactoryWithInitData, HexFormat, Jvm, Timed}
import org.apache.logging.log4j.core.util.NullOutputStream
import sbt.internal.util.ConsoleOut

import java.io.*
import java.nio.file.FileSystemException
import java.security.MessageDigest
import java.time.LocalDateTime
import scala.concurrent.duration.*
import scala.util.Using

@internal
class JvmWorkerImpl(args: JvmWorkerArgs[Unit]) extends JvmWorkerApi with AutoCloseable {
  import args.*

  private def fileLog(s: String): Unit =
    os.write.append(
      compilerBridge.workspace / "jvm-worker.log",
      s"[${LocalDateTime.now()}] $s\n",
      createFolders = true
    )

  private def fileAndDebugLog(log: Logger.Actions, s: String): Unit = {
    fileLog(s)
    log.debug(s)
  }

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker =
    ZincWorker(
      compilerBridge,
      jobs = jobs,
      compileToJar = compileToJar,
      zincLogDebug = zincLogDebug
    )

  override def compileJava(
      op: ZincCompileJava,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    fileLog(pprint.apply(op).render)
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    val result =
      Timed(zinc.compileJava(op, reporter = reporter, reportCachedProblems = reportCachedProblems))
    fileLog(s"Compilation took ${result.durationPretty}")
    result.result
  }

  override def compileMixed(
      op: ZincCompileMixed,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    fileLog(pprint.apply(op).render)
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    val result =
      Timed(zinc.compileMixed(op, reporter = reporter, reportCachedProblems = reportCachedProblems))
    fileLog(s"Compilation took ${result.durationPretty}")
    result.result
  }

  def scaladocJar(
      op: ZincScaladocJar,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    fileLog(pprint.apply(op).render)
    val zinc = zincApi(javaHome, JavaRuntimeOptions(Seq.empty))
    val result = Timed(zinc.scaladocJar(op))
    fileLog(s"Scaladoc took ${result.durationPretty}")
    result.result
  }

  override def close(): Unit = {
    zincLocalWorker.close()
    subprocessCache.close()
    close0() // make sure this is invoked last as it closes the classloader that we need for other `.close` calls
  }

  /**
   * Returns the [[ZincApi]] for either the local Zinc instance or the remote Zinc instance depending on the java
   * home and javac options.
   */
  private def zincApi(
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions
  )(using
      ctx: JvmWorkerApi.Ctx
  ): ZincApi = {
    val log = ctx.log
    val zincCtx = ZincWorker.InvocationContext(
      env = ctx.env,
      dest = ctx.dest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored
    )

    if (javaRuntimeOptions.options.isEmpty && javaHome.isEmpty) {
      fileLog("Using local Zinc instance")
      localZincApi(zincCtx, log)
    } else {
      fileLog(
        s"""Using remote Zinc instance:
           |  javaHome: $javaHome
           |  javaRuntimeOptions: $javaRuntimeOptions
           |""".stripMargin
      )
      val result = Timed(subprocessZincApi(javaHome, javaRuntimeOptions, zincCtx, log))
      fileLog(s"Remote Zinc instance acquired in ${result.durationPretty}")
      result.result
    }
  }

  private case class SubprocessCacheKey(
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions
  ) {
    def debugStr = s"javaHome=$javaHome, runtimeOptions=$runtimeOptions"

    def sha256: String = {
      val digest = MessageDigest.getInstance("sha256").digest(debugStr.getBytes("UTF-8"))
      HexFormat.bytesToHex(digest)
    }
  }
  private case class SubprocessCacheInitialize(
      taskDest: os.Path,
      log: Logger
  )
  private case class SubprocessCacheValue(
      port: Int,
      daemonDir: os.Path,
      launchedServer: LaunchedServer
  ) {
    def isRunning(): Boolean =
      launchedServer.isAlive

    def killProcess(): Unit = {
      os.remove(daemonDir / DaemonFiles.processId)
      while (isRunning()) Thread.sleep(1)

      // On Windows it takes some time until the file handles are released, so we have to wait for that as
      // well.
      if (scala.util.Properties.isWin) {
        val daemonLock = daemonDir / DaemonFiles.daemonLock

        def tryRemoving(): Boolean = {
          try { os.remove(daemonLock); true }
          catch { case _: FileSystemException => false }
        }

        while (!tryRemoving()) Thread.sleep(10)
      }
    }
  }
  private val subprocessCache = new CachedFactoryWithInitData[
    SubprocessCacheKey,
    SubprocessCacheInitialize,
    SubprocessCacheValue
  ] {
    override def maxCacheSize: Int = jobs

    override def cacheEntryStillValid(
        key: SubprocessCacheKey,
        initData: => SubprocessCacheInitialize,
        value: SubprocessCacheValue
    ): Boolean = value.isRunning()

    private var memoryLocksByDaemonDir = Map.empty[os.Path, MemoryLock]
    private def memLockFor(daemonDir: os.Path): MemoryLock = {
      memoryLocksByDaemonDir.synchronized {
        memoryLocksByDaemonDir.get(daemonDir) match {
          case Some(lock) => lock

          case None =>
            val lock = MemoryLock()
            memoryLocksByDaemonDir = memoryLocksByDaemonDir.updated(daemonDir, lock)
            lock
        }
      }
    }

    override def setup(
        key: SubprocessCacheKey,
        init: SubprocessCacheInitialize
    ): SubprocessCacheValue = {
      import init.log

      val workerDir = init.taskDest / "zinc-worker" / key.sha256
      val daemonDir = workerDir / "daemon"

      os.makeDir.all(daemonDir)
      os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
      os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.options.mkString("\n"))

      val mainClass = "mill.javalib.zinc.ZincWorkerMain"
      val locks = {
        val fileLocks = Locks.files(daemonDir.toString)
        Locks(
          // File locks are non-reentrant, so we need to lock on the memory lock first.
          //
          // We can get multiple lock acquisitions when we compile several modules in parallel,
          DoubleLock(memLockFor(daemonDir), fileLocks.launcherLock),
          // We never take the daemon lock, just check if it's already taken
          fileLocks.daemonLock
        )
      }

      fileAndDebugLog(log, s"Checking if $mainClass is already running for $key")
      val result = Timed(ServerLauncher.ensureServerIsRunning(
        locks,
        daemonDir.toNIO,
        () => {
          fileAndDebugLog(log, s"Starting JVM subprocess for $mainClass for $key")
          val process = Timed(Jvm.spawnProcess(
            mainClass = mainClass,
            mainArgs = Seq(daemonDir.toString),
            javaHome = key.javaHome,
            jvmArgs = key.runtimeOptions.options,
            classPath = classPath
          ))
          fileAndDebugLog(
            log,
            s"Starting JVM subprocess for $mainClass for $key took ${process.durationPretty}"
          )
          LaunchedServer.OsProcess(process.result.wrapped.toHandle)
        },
        fileAndDebugLog(log, _)
      ))
      fileAndDebugLog(
        log,
        s"Ensuring that server is running for $key took ${result.durationPretty}"
      )

      def onSuccess(launched: LaunchedServer) = {
        val serverInitWaitMillis = 5.seconds.toMillis
        val startTime = System.nanoTime()
        fileAndDebugLog(log, s"Reading server port: $daemonDir")
        val port =
          Timed(ServerLauncher.readServerPort(daemonDir.toNIO, startTime, serverInitWaitMillis))
        fileAndDebugLog(
          log,
          s"Reading server port for $daemonDir took ${port.durationPretty}."
        )
        fileAndDebugLog(log, s"Started $mainClass for $key on port ${port.result}")
        SubprocessCacheValue(port.result, daemonDir, launched)
      }

      result.result.fold(
        success => onSuccess(success.server),
        alreadyRunning => onSuccess(alreadyRunning.server),
        processDied =>
          throw IllegalStateException(
            s"""Failed to launch '$mainClass' for:
               |  javaHome = ${key.javaHome}
               |  runtimeOptions = ${key.runtimeOptions.options.mkString(",")}
               |  daemonDir = $daemonDir
               |
               |Failure:
               |$processDied
               |""".stripMargin
          )
      )
    }

    override def teardown(key: SubprocessCacheKey, value: SubprocessCacheValue): Unit = {
      value.killProcess()
    }
  }

  /** Gives you API for the [[zincLocalWorker]] instance. */
  private def localZincApi(
      zincCtx: ZincWorker.InvocationContext,
      log: Logger
  ): ZincApi = {
    val zincDeps = ZincWorker.InvocationDependencies(
      log = log,
      consoleOut = ConsoleOut.printStreamOut(log.streams.err)
    )

    zincLocalWorker.api(compilerBridgeData = ())(using zincCtx, zincDeps)
  }

  /**
   * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
   * instance for it.
   */
  private def subprocessZincApi(
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions,
      ctx: ZincWorker.InvocationContext,
      log: Logger
  ): ZincApi = {
    val cacheKey = SubprocessCacheKey(javaHome, runtimeOptions)

    def makeClientLogger() = new Logger.Actions {
      override def info(s: String): Unit = {
        fileLog(s"[LOGGER:INFO] $s")
        log.info(s)
      }

      override def debug(s: String): Unit = {
        fileLog(s"[LOGGER:DEBUG] $s")
        log.debug(s)
      }

      override def warn(s: String): Unit = {
        fileLog(s"[LOGGER:WARN] $s")
        log.warn(s)
      }

      override def error(s: String): Unit = {
        fileLog(s"[LOGGER:ERROR] $s")
        log.error(s)
      }

      override def ticker(s: String): Unit = {
        fileLog(s"[LOGGER:TICKER] $s")
        log.ticker(s)
      }
    }

    def withRpcClient[R](
        handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient]
    )(f: MillRpcClient[ZincWorkerRpcServer.ClientToServer, ZincWorkerRpcServer.ServerToClient] => R)
        : R = {
      subprocessCache.withValue(
        cacheKey,
        SubprocessCacheInitialize(compilerBridge.workspace, log)
      ) { case SubprocessCacheValue(port, daemonDir, _) =>
        Using.Manager { use =>
          val startTimeNanos = System.nanoTime()
          fileAndDebugLog(log, s"Connecting to $daemonDir on port $port")
          val socket = use(ServerLauncher.connectToServer(
            startTimeNanos,
            5.seconds.toMillis,
            port,
            s"From '${getClass.getName}'. Daemon directory: $daemonDir"
          ))
          val stdin = use(PipedInputStream())
          val stdout = use(PipedOutputStream())
          val streams = ServerLauncher.Streams(
            stdin,
            stdout,
            // stderr stream is not used in this case
            NullOutputStream.getInstance()
          )
          ServerLauncher.runWithConnection(
            socket,
            streams, /* closeConnectionAfterCommand */ true,
            /* sendInitData */ _ => {},
            () => {
              val serverToClient = use(BufferedReader(InputStreamReader(PipedInputStream(stdout))))
              val clientToServer = use(PrintStream(BufferedOutputStream(PipedOutputStream(stdin))))
              val wireTransport = MillRpcWireTransport.ViaStreams(
                s"ZincWorker,TCP ${socket.getRemoteSocketAddress} -> ${socket.getLocalSocketAddress}",
                serverToClient,
                clientToServer
              )

              val init = ZincWorkerRpcServer.Initialize(
                compilerBridgeWorkspace = compilerBridge.workspace,
                jobs = jobs,
                compileToJar = compileToJar,
                zincLogDebug = zincLogDebug
              )
              fileAndDebugLog(
                log,
                s"Connected to $daemonDir on port $port, sending init: ${pprint(init)}"
              )
              val client = MillRpcClient.create[
                ZincWorkerRpcServer.Initialize,
                ZincWorkerRpcServer.ClientToServer,
                ZincWorkerRpcServer.ServerToClient
              ](init, wireTransport, makeClientLogger())(handler)

              fileAndDebugLog(log, "Running command.")
              val result = Timed(f(client))
              fileAndDebugLog(log, s"Command finished in ${result.durationPretty}")
              result.result
            }
          ).result
        }.get
      }
    }

    new {
      override def compileJava(
          op: ZincCompileJava,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileJava(
            op,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def compileMixed(
          op: ZincCompileMixed,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileMixed(
            op,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def scaladocJar(op: ZincScaladocJar): Boolean = {
        withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.ScaladocJar(op)
          rpcClient(msg)
        }
      }
    }
  }

  /** Handles messages sent from the Zinc RPC server. */
  private def serverRpcToClientHandler(
      reporter: Option[CompileProblemReporter],
      log: Logger,
      cacheKey: SubprocessCacheKey
  )
      : MillRpcChannel[ZincWorkerRpcServer.ServerToClient] = {
    def acquireZincCompilerBridge(
        msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge
    ): msg.Response =
      compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization, data = ())

    def reportCompilationProblem(
        msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem
    ): msg.Response = {
      reporter match {
        case Some(reporter) => msg.problem match {
            case RpcCompileProblemReporterMessage.Start => reporter.start()
            case RpcCompileProblemReporterMessage.LogError(problem) => reporter.logError(problem)
            case RpcCompileProblemReporterMessage.LogWarning(problem) =>
              reporter.logWarning(problem)
            case RpcCompileProblemReporterMessage.LogInfo(problem) => reporter.logInfo(problem)
            case RpcCompileProblemReporterMessage.FileVisited(file) =>
              reporter.fileVisited(file.toNIO)
            case RpcCompileProblemReporterMessage.PrintSummary => reporter.printSummary()
            case RpcCompileProblemReporterMessage.Finish => reporter.finish()
            case RpcCompileProblemReporterMessage.NotifyProgress(percentage, total) =>
              reporter.notifyProgress(percentage = percentage, total = total)
          }

        case None =>
          log.warn(
            s"Received compilation problem from JVM worker (${cacheKey.debugStr}), but no reporter was provided, " +
              s"this is a bug in Mill. Ignoring the compilation problem for now.\n\n" +
              s"Problem: ${pprint.apply(msg)}"
          )
      }
    }

    (_, input) => {
      input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          acquireZincCompilerBridge(msg).asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem =>
          reportCompilationProblem(msg).asInstanceOf[input.Response]
      }
    }
  }

  private def toReporterMode(
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): ReporterMode = reporter match {
    case None => ReporterMode.NoReporter
    case Some(reporter) =>
      ReporterMode.Reporter(
        reportCachedProblems = reportCachedProblems,
        maxErrors = reporter.maxErrors
      )
  }
}
