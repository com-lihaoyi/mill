package mill.javalib.worker
import mill.api.*
import mill.api.daemon.internal.CompileProblemReporter
import mill.client.{LaunchedServer, ServerLauncher}
import mill.constants.DaemonFiles
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.*
import mill.javalib.internal.{RpcCompileProblemReporterMessage, ZincCompilerBridgeProvider}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.{CachedFactoryWithInitData, HexFormat}

import java.io.*
import java.nio.file.FileSystemException
import java.security.MessageDigest
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

/**
 * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
 * instance for it.
 */
class SubprocessZincApi(
    javaHome: Option[os.Path],
    runtimeOptions: JavaRuntimeOptions,
    ctx: ZincWorker.InvocationContext,
    log: Logger,
    subprocessCache: CachedFactoryWithInitData[
      SubprocessCacheKey,
      SubprocessCacheInitialize,
      SubprocessCacheValue
    ],
    compilerBridge: ZincCompilerBridgeProvider
) extends ZincApi {
  val cacheKey = SubprocessCacheKey(javaHome, runtimeOptions)

  def makeClientLogger() = new Logger.Actions {
    override def info(s: String): Unit = log.info(s)
    override def debug(s: String): Unit = log.debug(s)
    override def warn(s: String): Unit = log.warn(s)
    override def error(s: String): Unit = log.error(s)
    override def ticker(s: String): Unit = log.ticker(s)
  }

  def withRpcClient[R](
      handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient]
  )(f: MillRpcClient[ZincWorkerRpcServer.Request, ZincWorkerRpcServer.ServerToClient] => R)
      : R = {
    subprocessCache.withValue(
      cacheKey,
      SubprocessCacheInitialize(compilerBridge.workspace, log)
    ) { case SubprocessCacheValue(port, daemonDir, _) =>
      Using.Manager { use =>
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
              MillRpcWireTransport(
                debugName,
                serverToClient,
                clientToServer,
                writeSynchronizer = clientToServer
              )

            val init =
              ZincWorkerRpcServer.Initialize(compilerBridgeWorkspace = compilerBridge.workspace)

            val client = MillRpcClient.create[
              ZincWorkerRpcServer.Initialize,
              ZincWorkerRpcServer.Request,
              ZincWorkerRpcServer.ServerToClient
            ](init, wireTransport, makeClientLogger())(handler)

            f(client)
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

    input => {
      input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          acquireZincCompilerBridge(msg).asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem =>
          reportCompilationProblem(msg).asInstanceOf[input.Response]
      }
    }
  }

  override def apply(
      op: ZincOperation,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response = {
    withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
      val res = rpcClient(ZincWorkerRpcServer.Request(op, toReporterMode(reporter, reportCachedProblems), ctx))
      res.asInstanceOf[op.Response]
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
