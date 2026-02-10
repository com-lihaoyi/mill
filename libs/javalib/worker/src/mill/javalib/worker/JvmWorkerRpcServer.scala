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
    val workspaceRoot = os.Path(initialize.workspaceRoot.wrapped.toAbsolutePath.normalize())
    def normalizePath(path: os.Path): os.Path = {
      val nio = path.wrapped
      if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
      else {
        val raw = nio.toString.replace('\\', '/')
        val workspaceAlias = "out/mill-workspace"
        val homeAlias = "out/mill-home"

        def resolveFromAlias(base: os.Path, aliasIdx: Int, alias: String): os.Path = {
          val suffix = raw.substring(aliasIdx + alias.length).stripPrefix("/")
          if (suffix.isEmpty) base else base / os.RelPath(suffix)
        }

        if (raw == workspaceAlias) workspaceRoot
        else if (raw.startsWith(workspaceAlias + "/"))
          workspaceRoot / os.RelPath(raw.stripPrefix(workspaceAlias + "/"))
        else if (raw == homeAlias) os.home
        else if (raw.startsWith(homeAlias + "/"))
          os.home / os.RelPath(raw.stripPrefix(homeAlias + "/"))
        else {
          val workspaceIdx = raw.indexOf(workspaceAlias)
          if (workspaceIdx >= 0) resolveFromAlias(workspaceRoot, workspaceIdx, workspaceAlias)
          else {
            val homeIdx = raw.indexOf(homeAlias)
            if (homeIdx >= 0) resolveFromAlias(os.home, homeIdx, homeAlias)
            else os.Path(raw, os.pwd)
          }
        }
      }
    }
    val compilerBridgeWorkspace = normalizePath(initialize.compilerBridgeWorkspace)

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
          val normalizedCtx = input.ctx.copy(
            dest = normalizePath(input.ctx.dest),
            workspaceRoot = normalizePath(input.ctx.workspaceRoot)
          )
          val normalizedBridge = input.compilerBridge.map(_.map(normalizePath))
          worker.apply(
            op = input.op,
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
                  normalizedBridge.getOrElse {
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
