package mill.javalib.worker
import mill.api.*
import mill.api.daemon.internal.CompileProblemReporter
import mill.client.{LaunchedServer, ServerLauncher}
import mill.javalib.api.internal.*
import mill.javalib.internal.{RpcCompileProblemReporterMessage, ZincCompilerBridgeProvider}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.CachedFactoryWithInitData

import java.io.*
import scala.util.Using

/**
 * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
 * instance for it.
 */
class SubprocessZincApi(
    javaHome: Option[os.Path],
    runtimeOptions: Seq[String],
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

  /** Handles messages sent from the Zinc RPC server. */
  private def serverRpcToClientHandler(
      reporter: Option[CompileProblemReporter],
      log: Logger,
      cacheKey: SubprocessCacheKey
  ): MillRpcChannel[ZincWorkerRpcServer.ServerToClient] = {
    input =>
      input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization)
            .asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem =>
          val res =
            reporter match {
              case Some(reporter) => msg.problem match {
                  case RpcCompileProblemReporterMessage.Start => reporter.start()
                  case RpcCompileProblemReporterMessage.LogError(problem) =>
                    reporter.logError(problem)
                  case RpcCompileProblemReporterMessage.LogWarning(problem) =>
                    reporter.logWarning(problem)
                  case RpcCompileProblemReporterMessage.LogInfo(problem) =>
                    reporter.logInfo(problem)
                  case RpcCompileProblemReporterMessage.FileVisited(file) =>
                    reporter.fileVisited(file.toNIO)
                  case RpcCompileProblemReporterMessage.PrintSummary => reporter.printSummary()
                  case RpcCompileProblemReporterMessage.Finish => reporter.finish()
                  case RpcCompileProblemReporterMessage.NotifyProgress(progress, total) =>
                    reporter.notifyProgress(progress = progress, total = total)
                }

              case None =>
            }
          res.asInstanceOf[input.Response]
      }

  }

  override def apply(
      op: ZincOperation,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response = {
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
            ](init, wireTransport, makeClientLogger())(serverRpcToClientHandler(reporter, log, cacheKey))

            client.apply(ZincWorkerRpcServer.Request(
              op,
              reporter match {
                case None => ReporterMode.NoReporter
                case Some(reporter) => ReporterMode.Reporter(reportCachedProblems, reporter.maxErrors)
              },
              ctx
            )).asInstanceOf[op.Response]
          }
        )
      }.get
    }
  }
}

case class SubprocessCacheKey(javaHome: Option[os.Path], runtimeOptions: Seq[String])
case class SubprocessCacheInitialize(taskDest: os.Path, log: Logger)
case class SubprocessCacheValue(port: Int, daemonDir: os.Path, launchedServer: LaunchedServer)
