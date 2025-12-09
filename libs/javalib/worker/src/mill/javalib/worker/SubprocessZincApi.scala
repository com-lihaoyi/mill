package mill.javalib.worker
import mill.api.*
import mill.api.daemon.*
import mill.api.daemon.internal.CompileProblemReporter
import mill.client.lock.Locks
import mill.client.{LaunchedServer, ServerLauncher}
import mill.javalib.api.internal.*
import mill.javalib.api.internal.{RpcProblemMessage, ZincCompilerBridgeProvider}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.CachedFactoryBase

import java.io.*
import scala.util.Using

/**
 * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
 * instance for it.
 */
class SubprocessZincApi(
    javaHome: Option[os.Path],
    runtimeOptions: Seq[String],
    ctx: ZincWorker.LocalConfig,
    log: Logger,
    subprocessCache: CachedFactoryBase[
      SubprocessZincApi.Key,
      SubprocessZincApi.Key,
      SubprocessZincApi.Initialize,
      SubprocessZincApi.Value
    ],
    compilerBridge: ZincCompilerBridgeProvider
) extends ZincApi {
  val cacheKey = SubprocessZincApi.Key(javaHome, runtimeOptions)

  def makeClientLogger() = new Logger.Actions {
    override def info(s: String): Unit = log.info(s)
    override def debug(s: String): Unit = log.debug(s)
    override def warn(s: String): Unit = log.warn(s)
    override def error(s: String): Unit = log.error(s)
    override def ticker(s: String): Unit = log.ticker(s)
  }

  /** Handles messages sent from the Zinc RPC server. */
  private def serverRpcToClientHandler(
      reporter: Option[CompileProblemReporter]
  ): MillRpcChannel[ZincWorkerRpcServer.ServerToClient] = {
    input =>
      input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization)
            .asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportProblem =>
          val res =
            reporter match {
              case Some(reporter) => msg.problem match {
                  case RpcProblemMessage.Start => reporter.start()
                  case RpcProblemMessage.LogError(problem) => reporter.logError(problem)
                  case RpcProblemMessage.LogWarning(problem) => reporter.logWarning(problem)
                  case RpcProblemMessage.LogInfo(problem) => reporter.logInfo(problem)
                  case RpcProblemMessage.FileVisited(file) => reporter.fileVisited(file.toNIO)
                  case RpcProblemMessage.PrintSummary => reporter.printSummary()
                  case RpcProblemMessage.Finish => reporter.finish()
                  case RpcProblemMessage.NotifyProgress(progress, total) =>
                    reporter.notifyProgress(progress = progress, total = total)
                }

              case None =>
            }
          res.asInstanceOf[input.Response]
      }

  }

  override def apply(
      op: ZincOp,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response = {
    subprocessCache.withValue(
      cacheKey,
      SubprocessZincApi.Initialize(compilerBridge.workspace, log)
    ) {
      case SubprocessZincApi.Value(port, _, _, _) =>
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
              ](init, wireTransport, makeClientLogger())(serverRpcToClientHandler(reporter))

              client.apply(ZincWorkerRpcServer.Request(
                op,
                reporter match {
                  case None => ReporterMode.NoReporter
                  case Some(r) => ReporterMode.Reporter(reportCachedProblems, r.maxErrors)
                },
                ctx
              )).asInstanceOf[op.Response]
            }
          )
        }.get
    }
  }
}
object SubprocessZincApi {

  case class Key(javaHome: Option[os.Path], runtimeOptions: Seq[String])
  case class Initialize(taskDest: os.Path, log: Logger)
  case class Value(port: Int, daemonDir: os.Path, launchedServer: LaunchedServer, lock: Locks)
}
