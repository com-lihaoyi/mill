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
import mill.util.{CachedFactoryWithInitData, HexFormat, Jvm}
import org.apache.logging.log4j.core.util.NullOutputStream
import sbt.internal.util.ConsoleOut

import java.io.*
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.util.Using

@internal
class JvmWorkerImpl(args: JvmWorkerArgs[Unit]) extends JvmWorkerApi with AutoCloseable {
  import args.*

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
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    zinc.compileJava(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  override def compileMixed(
      op: ZincCompileMixed,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    zinc.compileMixed(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  def scaladocJar(
      op: ZincScaladocJar,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    val zinc = zincApi(javaHome, JavaRuntimeOptions(Seq.empty))
    zinc.scaladocJar(op)
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

    if (javaRuntimeOptions.options.isEmpty && javaHome.isEmpty) localZincApi(zincCtx, log)
    else subprocessZincApi(javaHome, javaRuntimeOptions, zincCtx, log)
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

      log.debug(s"Checking if $mainClass is already running for $key")
      val result = ServerLauncher.ensureServerIsRunning(
        locks,
        daemonDir.toNIO,
        () => {
          log.debug(s"Starting JVM subprocess for $mainClass for $key")
          val process = Jvm.spawnProcess(
            mainClass = mainClass,
            mainArgs = Seq(daemonDir.toString),
            javaHome = key.javaHome,
            jvmArgs = key.runtimeOptions.options,
            classPath = classPath
          )
          LaunchedServer.OsProcess(process.wrapped.toHandle)
        },
        log.debug
      )

      def onSuccess(launched: LaunchedServer) = {
        val serverInitWaitMillis = 5.seconds.toMillis
        val startTime = System.nanoTime()
        log.debug(s"Reading server port: $daemonDir")
        val port = ServerLauncher.readServerPort(daemonDir.toNIO, startTime, serverInitWaitMillis)
        log.debug(s"Started $mainClass for $key on port $port")
        SubprocessCacheValue(port, daemonDir, launched)
      }

      result.fold(
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
          log.debug(s"Connecting to $daemonDir on port $port")
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
              val clientToServer = use(PrintStream(PipedOutputStream(stdin)))
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
              val client = MillRpcClient.create[
                ZincWorkerRpcServer.Initialize,
                ZincWorkerRpcServer.ClientToServer,
                ZincWorkerRpcServer.ServerToClient
              ](init, wireTransport, log)(handler)

              f(client)
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
