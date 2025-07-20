package mill.javalib.zinc

import mill.api.JsonFormatters.*
import mill.api.daemon.Logger
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.internal.{RpcCompileProblemReporterMessage, ZincCompilerBridge}
import mill.javalib.worker.JavaCompilerOptions
import mill.rpc.{MillRpcChannel, MillRpcMessage, MillRpcServerImpl, MillRpcWireTransport}
import sbt.internal.util.ConsoleOut
import upickle.default.ReadWriter

import java.io.PrintStream

object ZincWorkerMain
    extends MillRpcServerImpl[
      ZincWorkerMain.Initialize,
      ZincWorkerMain.ClientToServer,
      ZincWorkerMain.ServerToClient
    ](MillRpcWireTransport.ViaStdinAndStdout) {

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
        upstreamCompileOutput: Seq[CompilationResult],
        sources: Seq[os.Path],
        compileClasspath: Seq[os.Path],
        javacOptions: JavaCompilerOptions,
        reporterMode: ReporterMode,
        incrementalCompilation: Boolean,
        ctx: ZincWorker.InvocationContext
    ) extends ClientToServer {
      override type Response = Either[String, CompilationResult]
    }
  }

  sealed trait ServerToClient extends MillRpcMessage derives ReadWriter
  object ServerToClient {
    case class InvokeZincCompilerBridgeCompile(scalaVersion: String, scalaOrganization: String)
        extends ServerToClient {
      override type Response = ZincCompilerBridge.CompileResult[os.Path]
    }

    case class Console(msg: RpcConsole.Message) extends ServerToClient {
      override type Response = msg.Response
    }

    /**
     * @param compilationRequestId request id for the message requesting the compilation.
     */
    case class ReportCompilationProblem(compilationRequestId: Long, problem: RpcCompileProblemReporterMessage)
        extends ServerToClient {
      override type Response = problem.Response
    }
  }

  override def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer] = {
    val zincCompilerBridge = ZincCompilerBridge.Provider(
      taskDest = initialize.taskDest,
      logInfo = log.info,
      compile = (scalaVersion, scalaOrganization) =>
        serverToClient(ServerToClient.InvokeZincCompilerBridgeCompile(
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization
        ))
    )
    val worker = ZincWorker(
      zincCompilerBridge,
      jobs = initialize.jobs,
      compileToJar = initialize.compileToJar,
      zincLogDebug = initialize.zincLogDebug
    )

    // This is an ugly hack. `ConsoleOut` is sealed but we need to provide a way to send these logs to the Mill server
    // over RPC, so we hijack `PrintStream` by overriding the methods that `ConsoleOut` uses.
    //
    // This is obviously extra fragile, but I couldn't find a better way to do it.
    val consoleOut = ConsoleOut.printStreamOut(new PrintStream(null) {
      def send(msg: RpcConsole.Message): Unit = serverToClient(ServerToClient.Console(msg))

      override def print(s: String): Unit = send(RpcConsole.Message.Print(s))
      override def println(s: String): Unit = print(s + "\n")
      override def println(): Unit = print("\n")
      override def flush(): Unit = send(RpcConsole.Message.Flush)
    })

    val deps = ZincWorker.InvocationDependencies(log, consoleOut)

    def reporter(requestId: Long, maxErrors: Int) = RpcCompileProblemReporter(
      maxErrors = maxErrors,
      send = msg => serverToClient(ServerToClient.ReportCompilationProblem(requestId, msg))
    )

    def reporterAsOption(requestId: Long, mode: ReporterMode): Option[CompileProblemReporter] = mode match {
      case ReporterMode.NoReporter => None
      case r: ReporterMode.Reporter => Some(reporter(requestId = requestId, maxErrors = r.maxErrors))
    }

    new MillRpcChannel[ClientToServer] {
      override def apply(requestId: Long, input: ClientToServer): input.Response = {
        input match {
          case msg: ClientToServer.CompileJava => compileJava(requestId, msg).asInstanceOf
        }
      }

      private def compileJava(requestId: Long, msg: ClientToServer.CompileJava): msg.Response = {
        worker.compileJava(
          upstreamCompileOutput = msg.upstreamCompileOutput,
          sources = msg.sources,
          compileClasspath = msg.compileClasspath,
          javacOptions = msg.javacOptions,
          reporter = reporterAsOption(requestId, msg.reporterMode),
          reportCachedProblems = msg.reporterMode.reportCachedProblems,
          incrementalCompilation = msg.incrementalCompilation
        )(using msg.ctx, deps).toEither
      }
    }
  }
}
