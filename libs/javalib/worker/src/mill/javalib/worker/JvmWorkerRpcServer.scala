package mill.javalib.worker

import mill.api.JsonFormatters.*
import mill.api.daemon.Logger
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.PathRef
import mill.javalib.api.CompilationResult
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
    val workspaceRoot =
      SerializedPathNormalizer.fromPotentiallyRelativeSerializedPath(initialize.workspaceRoot)
    log.info(s"DEBUG rpc.init initialize.workspaceRoot=${initialize.workspaceRoot.wrapped}")
    log.info(s"DEBUG rpc.init buildCtx.workspaceRoot=${mill.api.BuildCtx.workspaceRoot.wrapped}")
    log.info(s"DEBUG rpc.init normalized.workspaceRoot=${workspaceRoot.wrapped}")
    val compilerBridgeWorkspace =
      SerializedPathNormalizer.fromPotentiallyRelativeSerializedPath(
        initialize.compilerBridgeWorkspace,
        workspaceRoot
      )
    log.info(
      s"DEBUG rpc.init initialize.compilerBridgeWorkspace=${initialize.compilerBridgeWorkspace.wrapped}"
    )
    log.info(s"DEBUG rpc.init normalized.compilerBridgeWorkspace=${compilerBridgeWorkspace.wrapped}")

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
      def normalizePath(path: os.Path): os.Path =
        SerializedPathNormalizer.fromPotentiallyRelativeSerializedPath(path, workspaceRoot)

      def normalizeCompilationResult(
          result: CompilationResult
      ): CompilationResult = {
        val classes = result.classes
        result.copy(
          analysisFile = normalizePath(result.analysisFile),
          classes = PathRef(normalizePath(classes.path), quick = classes.quick)
        )
      }

      def normalizeOp(op: ZincOp): ZincOp = op match {
        case msg: ZincOp.CompileJava =>
          msg.copy(
            upstreamCompileOutput = msg.upstreamCompileOutput.map(normalizeCompilationResult),
            sources = msg.sources.map(normalizePath),
            compileClasspath = msg.compileClasspath.map(normalizePath),
            workDir = normalizePath(msg.workDir)
          )
        case msg: ZincOp.CompileMixed =>
          msg.copy(
            upstreamCompileOutput = msg.upstreamCompileOutput.map(normalizeCompilationResult),
            sources = msg.sources.map(normalizePath),
            compileClasspath = msg.compileClasspath.map(normalizePath),
            workDir = normalizePath(msg.workDir)
          )
        case msg: ZincOp.ScaladocJar =>
          msg.copy(workDir = normalizePath(msg.workDir))
        case msg: ZincOp.DiscoverTests =>
          msg.copy(
            runCp = msg.runCp.map(normalizePath),
            testCp = msg.testCp.map(normalizePath)
          )
        case msg: ZincOp.GetTestTasks =>
          msg.copy(
            runCp = msg.runCp.map(normalizePath),
            testCp = msg.testCp.map(normalizePath)
          )
        case msg: ZincOp.DiscoverJunit5Tests =>
          msg.copy(
            runCp = msg.runCp.map(normalizePath),
            testCp = msg.testCp.map(normalizePath),
            classesDir = msg.classesDir.map(normalizePath)
          )
      }

      override def apply(input: JvmWorkerRpcServer.Request): input.Response = {
        setIdle.doWork {
          val normalizedCtx = input.ctx.copy(
            dest = normalizePath(input.ctx.dest),
            workspaceRoot = normalizePath(input.ctx.workspaceRoot)
          )
          val normalizedOp = normalizeOp(input.op)
          worker.apply(
            op = normalizedOp.asInstanceOf[input.op.type],
            reporter = reporterAsOption(input.reporterMode),
            reportCachedProblems = input.reporterMode.reportCachedProblems,
            normalizedCtx,
            ZincWorker.ProcessConfig(
              log,
              consoleOut,
              ZincCompilerBridgeProvider(
                workspace = compilerBridgeWorkspace,
                logInfo = log.info,
                acquire = (scalaVersion, scalaOrganization) =>
                  input.compilerBridge.map(
                    _.map(SerializedPathNormalizer.fromPotentiallyRelativeSerializedPath)
                  ).getOrElse {
                    throw new IllegalStateException(
                      s"Missing compiler bridge for $scalaOrganization:$scalaVersion."
                    )
                  }
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
  case class Initialize(compilerBridgeWorkspace: os.Path, workspaceRoot: os.Path)
      derives ReadWriter

  enum ReporterMode(val reportCachedProblems: Boolean) derives ReadWriter {
    case NoReporter extends ReporterMode(false)
    case Reporter(reportCachedProblems0: Boolean, maxErrors: Int)
        extends ReporterMode(reportCachedProblems0)
  }

  case class Request(
      op: ZincOp,
      reporterMode: ReporterMode,
      ctx: ZincWorker.LocalConfig,
      compilerBridge: Option[ZincCompilerBridgeProvider.AcquireResult[os.Path]]
  ) extends MillRpcChannel.Message derives upickle.ReadWriter {
    type Response = op.Response
  }

  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter
  object ServerToClient {
    case class ReportProblem(problem: RpcProblemMessage)
        extends ServerToClient, MillRpcChannel.Message {
      type Response = Unit
    }
  }
}
