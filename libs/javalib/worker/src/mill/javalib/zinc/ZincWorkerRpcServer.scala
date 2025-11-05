package mill.javalib.zinc

import mill.api.JsonFormatters.*
import mill.api.daemon.Logger
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.internal.*
import mill.javalib.internal.{RpcCompileProblemReporterMessage, ZincCompilerBridgeProvider}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.rpc.*
import mill.server.Server
import org.apache.logging.log4j.core.util.NullOutputStream
import sbt.internal.util.ConsoleOut
import upickle.ReadWriter

import java.io.PrintStream

class ZincWorkerRpcServer(
    worker: ZincWorker,
    serverName: String,
    transport: MillRpcWireTransport,
    setIdle: Server.SetIdle,
    writeToLocalLog: String => Unit
) extends MillRpcServerImpl[
      ZincWorkerRpcServer.Initialize,
      ZincWorkerRpcServer.Request,
      ZincWorkerRpcServer.ServerToClient
    ](serverName, transport, writeToLocalLog) {
  import ZincWorkerRpcServer.*

  override def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      clientStdout: RpcConsole,
      clientStderr: RpcConsole,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ZincWorkerRpcServer.Request] = setIdle.doWork {
    val result = {
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

      def makeCompilerBridge() =
        ZincCompilerBridgeProvider(
          workspace = initialize.compilerBridgeWorkspace,
          logInfo = log.info,
          acquire = (scalaVersion, scalaOrganization) =>
            serverToClient(
              ServerToClient.AcquireZincCompilerBridge(
                scalaVersion = scalaVersion,
                scalaOrganization = scalaOrganization
              )
            )
        )

      def makeDeps() = ZincWorker.InvocationDependencies(log, consoleOut, makeCompilerBridge())

      def reporter(maxErrors: Int) = RpcCompileProblemReporter(
        maxErrors = maxErrors,
        send = msg => serverToClient(ServerToClient.ReportCompilationProblem(msg))
      )

      def reporterAsOption(
          mode: ReporterMode
      ): Option[CompileProblemReporter] = mode match {
        case ReporterMode.NoReporter => None
        case r: ReporterMode.Reporter => Some(reporter(maxErrors = r.maxErrors))
      }

      new MillRpcChannel[ZincWorkerRpcServer.Request] {
        override def apply(input: ZincWorkerRpcServer.Request): input.Response = {
          val ZincWorkerRpcServer.Request(op, reporterMode, ctx) = input
          setIdle.doWork {
            worker.apply(
              op = op,
              reporter = reporterAsOption(reporterMode),
              reportCachedProblems = reporterMode.reportCachedProblems,
              ctx,
              makeDeps()
            ).asInstanceOf[input.Response]
          }
        }
      }
    }

    result
  }
}
object ZincWorkerRpcServer {

  /**
   * @param compilerBridgeWorkspace The workspace to use for the compiler bridge.
   */
  case class Initialize(
      compilerBridgeWorkspace: os.Path
  ) derives ReadWriter

  sealed trait ReporterMode derives ReadWriter {
    def reportCachedProblems: Boolean
  }
  object ReporterMode {
    case object NoReporter extends ReporterMode {
      override def reportCachedProblems: Boolean = false
    }

    case class Reporter(reportCachedProblems: Boolean, maxErrors: Int) extends ReporterMode
  }

  case class Request(
      op: ZincOperation,
      reporterMode: ReporterMode,
      ctx: ZincWorker.InvocationContext
  ) extends MillRpcMessage
      derives upickle.ReadWriter {
    type Response = op.Response
  }
  sealed trait ServerToClient extends MillRpcMessage derives ReadWriter
  object ServerToClient {
    case class AcquireZincCompilerBridge(scalaVersion: String, scalaOrganization: String)
        extends ServerToClient {
      override type Response = ZincCompilerBridgeProvider.AcquireResult[os.Path]
    }

    /**
     * @param compilationRequestId request id for the message requesting the compilation.
     */
    case class ReportCompilationProblem(
        problem: RpcCompileProblemReporterMessage
    ) extends ServerToClient, MillRpcMessage {
      type Response = Unit
    }
  }
}
