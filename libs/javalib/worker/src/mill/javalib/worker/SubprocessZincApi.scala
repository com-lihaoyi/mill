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
import mill.util.{CachedFactoryWithInitData, HexFormat, Jvm, RequestId, RequestIdFactory, Timed}
import sbt.internal.util.ConsoleOut

import java.io.*
import java.nio.file.FileSystemException
import java.security.MessageDigest
import java.time.LocalDateTime
import scala.util.Using
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
                                              log: Logger,
                                              requestId: RequestId
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
/**
 * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
 * instance for it.
 */
class SubprocessZincApi(
                               javaHome: Option[os.Path],
                               runtimeOptions: JavaRuntimeOptions,
                               ctx: ZincWorker.InvocationContext,
                               log: Logger,
                       fileLog: String => Unit,
                               subprocessCache: CachedFactoryWithInitData[
                                 SubprocessCacheKey,
                                 SubprocessCacheInitialize,
                                 SubprocessCacheValue
                               ]
                             )(using requestId: RequestId) extends ZincApi {
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
      SubprocessCacheInitialize(compilerBridge.workspace, log, requestId)
    ) { case SubprocessCacheValue(port, daemonDir, _) =>
      Using.Manager { use =>
        fileAndDebugLog(log, s"Connecting to $daemonDir on port $port")
        val socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)
        val debugName =
          s"ZincWorker,TCP ${socket.getRemoteSocketAddress} -> ${socket.getLocalSocketAddress}"
        ServerLauncher.runWithConnection(
          socket,
          /* closeConnectionAfterCommand */ true,
          /* sendInitData */ _ => {},
          (in, out) => {
            val serverToClient = use(BufferedReader(InputStreamReader(in)))
            val clientToServer = use(PrintStream(out))
            val wireTransport =
              MillRpcWireTransport.ViaStreams(
                debugName,
                serverToClient,
                clientToServer,
                writeSynchronizer = clientToServer
              )

            val init =
              ZincWorkerRpcServer.Initialize(compilerBridgeWorkspace = compilerBridge.workspace)
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
        )
      }.get
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
      compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization)

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
          case RpcCompileProblemReporterMessage.NotifyProgress(progress, total) =>
            reporter.notifyProgress(progress = progress, total = total)
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

  override def discoverTests(op: mill.javalib.api.internal.ZincDiscoverTests): Seq[String] = {
    withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
      val msg = ZincWorkerRpcServer.ClientToServer.DiscoverTests(op)
      rpcClient(msg)
    }
  }

  override def getTestTasks(op: mill.javalib.api.internal.ZincGetTestTasks): Seq[String] = {
    withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
      val msg = ZincWorkerRpcServer.ClientToServer.GetTestTasks(op)
      rpcClient(msg)
    }
  }

  override def discoverJunit5Tests(op: mill.javalib.api.internal.ZincDiscoverJunit5Tests)
  : Seq[String] = {
    withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
      val msg = ZincWorkerRpcServer.ClientToServer.DiscoverJunit5Tests(op)
      rpcClient(msg)
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

