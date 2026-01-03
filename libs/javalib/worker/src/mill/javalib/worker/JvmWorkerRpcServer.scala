package mill.javalib.worker

import mill.api.JsonFormatters.*
import mill.api.daemon.Logger
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.internal.*
import mill.javalib.worker.JvmWorkerRpcServer.ReporterMode
import mill.javalib.zinc.ZincWorker
import mill.javalib.worker.JvmWorkerRpcServer
import mill.rpc.*
import mill.server.Server
import org.apache.logging.log4j.core.util.NullOutputStream
import sbt.internal.util.ConsoleOut
import upickle.ReadWriter

import java.io.PrintStream

class JvmWorkerRpcServer(
    worker: ZincWorker,
    serverName: String,
    transport: MillRpcWireTransport,
    setIdle: Server.SetIdle,
    writeToLocalLog: String => Unit
) extends MillRpcServer[
      JvmWorkerRpcServer.Initialize,
      JvmWorkerRpcServer.Request,
      JvmWorkerRpcServer.ServerToClient
    ](serverName, transport, writeToLocalLog) {
  import JvmWorkerRpcServer.*

  override def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      clientStdout: RpcConsole,
      clientStderr: RpcConsole,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[JvmWorkerRpcServer.Request] = setIdle.doWork {
    // This is an ugly hack. `ConsoleOut` is sealed, but we need to provide a way to send these logs to the Mill server
    // over RPC, so we hijack `PrintStream` by overriding the methods that `ConsoleOut` uses.
    //
    // This is obviously extra fragile, but I couldn't find a better way to do it.
    val consoleOut = ConsoleOut.printStreamOut(new PrintStream(NullOutputStream.getInstance()) {
      override def print(s: String): Unit = clientStderr.print(s)
      override def println(s: String): Unit = print(s + "\n")
      override def println(): Unit = print("\n")
      override def flush(): Unit = clientStderr.flush()
    })

    def reporterAsOption(mode: ReporterMode): Option[CompileProblemReporter] = mode match {
      case ReporterMode.NoReporter => None
      case r: ReporterMode.Reporter =>
        def send(msg: RpcProblemMessage) = serverToClient(ServerToClient.ReportProblem(msg))
        Some(mill.javalib.zinc.RpcCompileProblemReporter(r.maxErrors, send))
    }

    new MillRpcChannel[JvmWorkerRpcServer.Request] {
      override def apply(input: JvmWorkerRpcServer.Request): input.Response = {
        setIdle.doWork {
          worker.apply(
            op = input.op,
            reporter = reporterAsOption(input.reporterMode),
            reportCachedProblems = input.reporterMode.reportCachedProblems,
            input.ctx,
            ZincWorker.ProcessConfig(
              log,
              consoleOut,
              ZincCompilerBridgeProvider(
                workspace = initialize.compilerBridgeWorkspace,
                logInfo = log.info,
                acquire = (scalaVersion, scalaOrganization) =>
                  serverToClient(
                    ServerToClient.AcquireZincCompilerBridge(scalaVersion, scalaOrganization)
                  )
              )
            )
          ).asInstanceOf[input.Response]
        }
      }
    }
  }
}

object JvmWorkerRpcServer {

  /**
   * @param compilerBridgeWorkspace The workspace to use for the compiler bridge.
   */
  case class Initialize(compilerBridgeWorkspace: os.Path) derives ReadWriter

  enum ReporterMode(val reportCachedProblems: Boolean) derives ReadWriter {
    case NoReporter extends ReporterMode(false)
    case Reporter(reportCachedProblems0: Boolean, maxErrors: Int)
        extends ReporterMode(reportCachedProblems0)
  }

  case class Request(op: ZincOp, reporterMode: ReporterMode, ctx: ZincWorker.LocalConfig)
      extends MillRpcChannel.Message derives upickle.ReadWriter {
    type Response = op.Response
  }

  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter
  object ServerToClient {
    case class AcquireZincCompilerBridge(scalaVersion: String, scalaOrganization: String)
        extends ServerToClient {
      override type Response = ZincCompilerBridgeProvider.AcquireResult[os.Path]
    }

    case class ReportProblem(problem: RpcProblemMessage)
        extends ServerToClient, MillRpcChannel.Message {
      type Response = Unit
    }
  }
}
