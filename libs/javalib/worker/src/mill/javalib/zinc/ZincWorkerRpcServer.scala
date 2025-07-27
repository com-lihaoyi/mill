package mill.javalib.zinc

import mill.api.JsonFormatters.*
import mill.api.PathRef
import mill.api.daemon.{Logger, Result}
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.internal.{RpcCompileProblemReporterMessage, ZincCompilerBridge}
import mill.javalib.worker.JavaCompilerOptions
import mill.rpc.*
import org.apache.logging.log4j.core.util.NullOutputStream
import sbt.internal.util.ConsoleOut
import upickle.default.ReadWriter

import java.io.PrintStream

class ZincWorkerRpcServer
    extends MillRpcServerImpl[
      ZincWorkerRpcServer.Initialize,
      ZincWorkerRpcServer.ClientToServer,
      ZincWorkerRpcServer.ServerToClient
    ](MillRpcWireTransport.ViaStdinAndStdout) {
  import ZincWorkerRpcServer.*

  override def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      clientStdout: RpcConsole,
      clientStderr: RpcConsole,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer] = {
    val zincCompilerBridge = ZincCompilerBridge[MillRpcRequestId](
      taskDest = initialize.taskDest,
      logInfo = log.info,
      acquire = (scalaVersion, scalaOrganization, clientRequestId) =>
        serverToClient(
          clientRequestId,
          ServerToClient.AcquireZincCompilerBridge(
            scalaVersion = scalaVersion,
            scalaOrganization = scalaOrganization
          )
        )
    )
    val worker = ZincWorker(
      zincCompilerBridge,
      jobs = initialize.jobs,
      compileToJar = initialize.compileToJar,
      zincLogDebug = initialize.zincLogDebug
    )

    val deps = {
      // This is an ugly hack. `ConsoleOut` is sealed but we need to provide a way to send these logs to the Mill server
      // over RPC, so we hijack `PrintStream` by overriding the methods that `ConsoleOut` uses.
      //
      // This is obviously extra fragile, but I couldn't find a better way to do it.
      val consoleOut = ConsoleOut.printStreamOut(new PrintStream(NullOutputStream.getInstance()) {
        override def print(s: String): Unit = clientStderr.print(s)
        override def println(s: String): Unit = print(s + "\n")
        override def println(): Unit = print("\n")
        override def flush(): Unit = clientStderr.flush()
      })

      ZincWorker.InvocationDependencies(log, consoleOut)
    }

    def reporter(clientRequestId: MillRpcRequestId, maxErrors: Int) = RpcCompileProblemReporter(
      maxErrors = maxErrors,
      send = msg =>
        serverToClient(
          clientRequestId,
          ServerToClient.ReportCompilationProblem(clientRequestId, msg)
        )
    )

    def reporterAsOption(
        clientRequestId: MillRpcRequestId,
        mode: ReporterMode
    ): Option[CompileProblemReporter] = mode match {
      case ReporterMode.NoReporter => None
      case r: ReporterMode.Reporter =>
        Some(reporter(clientRequestId = clientRequestId, maxErrors = r.maxErrors))
    }

    new MillRpcChannel[ClientToServer] {
      override def apply(requestId: MillRpcRequestId, input: ClientToServer): input.Response = {
        input match {
          case msg: ClientToServer.CompileJava =>
            compileJava(requestId, msg).asInstanceOf[input.Response]
          case msg: ClientToServer.CompileMixed =>
            compileMixed(requestId, msg).asInstanceOf[input.Response]
          case msg: ClientToServer.DocJar => docJar(requestId, msg).asInstanceOf[input.Response]
        }
      }

      private def compileJava(
          clientRequestId: MillRpcRequestId,
          msg: ClientToServer.CompileJava
      ): msg.Response = {
        worker.compileJava(
          op = msg.op,
          reporter = reporterAsOption(clientRequestId, msg.reporterMode),
          reportCachedProblems = msg.reporterMode.reportCachedProblems,
        )(using msg.ctx, deps)
      }

      private def compileMixed(
          clientRequestId: MillRpcRequestId,
          msg: ClientToServer.CompileMixed
      ): msg.Response = {
        worker.compileMixed(
          msg.op,
          reporter = reporterAsOption(clientRequestId, msg.reporterMode),
          reportCachedProblems = msg.reporterMode.reportCachedProblems,
          compilerBridgeData = clientRequestId
        )(using msg.ctx, deps)
      }

      private def docJar(
          clientRequestId: MillRpcRequestId,
          msg: ClientToServer.DocJar
      ): msg.Response =
        worker.scaladocJar(msg.op, compilerBridgeData = clientRequestId)
    }
  }
}
object ZincWorkerRpcServer {

  /**
   * @param taskDest the task's destination folder.
   */
  case class Initialize(taskDest: os.Path, jobs: Int, compileToJar: Boolean, zincLogDebug: Boolean)
      derives ReadWriter

  sealed trait ReporterMode derives ReadWriter {
    def reportCachedProblems: Boolean
  }
  object ReporterMode {
    case object NoReporter extends ReporterMode {
      override def reportCachedProblems: Boolean = false
    }

    case class Reporter(reportCachedProblems: Boolean, maxErrors: Int) extends ReporterMode
  }

  sealed trait ClientToServer extends MillRpcMessage derives ReadWriter
  object ClientToServer {
    case class CompileJava(
        op: ZincCompileJava,
        reporterMode: ReporterMode,
        ctx: ZincWorker.InvocationContext
    ) extends ClientToServer {
      override type Response = Result[CompilationResult]
    }

    case class CompileMixed(
        op: ZincCompileMixed,
        reporterMode: ReporterMode,
        ctx: ZincWorker.InvocationContext
    ) extends ClientToServer {
      override type Response = Result[CompilationResult]
    }

    case class DocJar(
        op: ZincScaladocJar
    ) extends ClientToServer {
      override type Response = Boolean
    }
  }

  sealed trait ServerToClient extends MillRpcMessage derives ReadWriter
  object ServerToClient {
    case class AcquireZincCompilerBridge(scalaVersion: String, scalaOrganization: String)
        extends ServerToClient {
      override type Response = ZincCompilerBridge.AcquireResult[os.Path]
    }

    /**
     * @param compilationRequestId request id for the message requesting the compilation.
     */
    case class ReportCompilationProblem(
        compilationRequestId: MillRpcRequestId,
        problem: RpcCompileProblemReporterMessage
    ) extends ServerToClient, MillRpcMessage.NoResponse
  }
}
