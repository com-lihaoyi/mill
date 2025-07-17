package mill.javalib.zinc

import mill.api.JsonFormatters.*
import mill.api.daemon.Logger
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.internal.ZincCompilerBridge
import mill.javalib.worker.JavaCompilerOptions
import mill.rpc.{MillRpcChannel, MillRpcMessage, MillRpcServerImpl}
import upickle.default.ReadWriter

object ZincWorkerMain
    extends MillRpcServerImpl[
      ZincWorkerMain.Initialize,
      ZincWorkerMain.ClientToServer,
      ZincWorkerMain.ServerToClient
    ] {

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

    case class Reporter(reportCachedProblems: Boolean) extends ReporterMode
  }

  sealed trait ClientToServer extends MillRpcMessage derives ReadWriter
  object ClientToServer {
    case class CompileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javacOptions: JavaCompilerOptions,
      reporter: ReporterMode,
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
  }

  override def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer] = {
    val zincCompilerBridge = ZincCompilerBridge.Provider(
      taskDest = initialize.taskDest,
      logInfo = log.info,
      compile = (scalaVersion, scalaOrganization) => {
        val message = ServerToClient.InvokeZincCompilerBridgeCompile(
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization
        )
        serverToClient(message)
      }
    )
    val worker = ZincWorker(
      zincCompilerBridge,
      jobs = initialize.jobs,
      compileToJar = initialize.compileToJar,
      zincLogDebug = initialize.zincLogDebug
    )
    val deps = ZincWorker.InvocationDependencies(log, errorStream = ???)

    val reporter = RpcCompileProblemReporter(
      maxErrors = ???,
      send = msg => ???
    )

    def reporterAsOption(mode: ReporterMode): Option[CompileProblemReporter] = mode match {
      case ReporterMode.NoReporter => None
      case _: ReporterMode.Reporter => Some(reporter)
    }

    new MillRpcChannel[ClientToServer] {
      override def apply(input: ClientToServer): input.Response = {
        input match {
          case msg: ClientToServer.CompileJava => compileJava(msg).asInstanceOf
        }
      }

      private def compileJava(msg: ClientToServer.CompileJava): msg.Response = {
        worker.compileJava(
          upstreamCompileOutput = msg.upstreamCompileOutput,
          sources = msg.sources,
          compileClasspath = msg.compileClasspath,
          javacOptions = msg.javacOptions,
          reporter = reporterAsOption(msg.reporter),
          reportCachedProblems = msg.reporter.reportCachedProblems,
          incrementalCompilation = msg.incrementalCompilation,
        )(using msg.ctx, deps).toEither
      }
    }
  }
}
