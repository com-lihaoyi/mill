package mill.launcher

import mill.api.daemon.{LauncherSubprocess, Logger}
import mill.rpc.*
import upickle.ReadWriter

import java.io.{BufferedReader, PrintStream}

/**
 * RPC message types for launcher-daemon communication.
 */
object DaemonRpc {
  // ReadWriter for LauncherSubprocess.Config (defined in core/api/daemon which has no upickle dependency)
  given ReadWriter[LauncherSubprocess.Config] = upickle.default.macroRW
  case class Initialize(
      interactive: Boolean,
      clientMillVersion: String,
      clientJavaVersion: String,
      clientJvmOpts: Seq[String],
      args: Seq[String],
      env: Map[String, String],
      userSpecifiedProperties: Map[String, String]
  ) derives ReadWriter

  sealed trait ClientToServer extends MillRpcChannel.Message derives ReadWriter
  object ClientToServer {
    case class RunCommand() extends ClientToServer { type Response = RunCommandResult }
  }

  case class RunCommandResult(exitCode: Int, metadata: String = "") derives ReadWriter

  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter
  object ServerToClient {

    /** Request to run a subprocess on the launcher with inherited I/O. */
    case class RunSubprocess(config: mill.api.daemon.LauncherSubprocess.Config)
        extends ServerToClient {
      type Response = SubprocessResult
    }
  }

  case class SubprocessResult(exitCode: Int) derives ReadWriter

  def createClient(
      initialize: Initialize,
      serverToClient: BufferedReader,
      clientToServer: PrintStream,
      stdout: RpcConsole.Message => Unit = RpcConsole.stdoutHandler,
      stderr: RpcConsole.Message => Unit = RpcConsole.stderrHandler,
      runSubprocess: ServerToClient.RunSubprocess => SubprocessResult = defaultRunSubprocess
  ): MillRpcClient[ClientToServer, ServerToClient] = {
    val transport = MillRpcWireTransport(
      name = "DaemonRpcClient",
      serverToClient = serverToClient,
      clientToServer = clientToServer,
      writeSynchronizer = new Object
    )

    val log = Logger.Actions.noOp

    val serverMessageHandler = new MillRpcChannel[ServerToClient] {
      override def apply(input: ServerToClient): input.Response = input match {
        case req: ServerToClient.RunSubprocess =>
          runSubprocess(req).asInstanceOf[input.Response]
      }
    }

    MillRpcClient.create[Initialize, ClientToServer, ServerToClient](
      initialize = initialize,
      wireTransport = transport,
      log = log,
      stdout = stdout,
      stderr = stderr
    )(serverMessageHandler)
  }

  def defaultRunSubprocess(req: ServerToClient.RunSubprocess): SubprocessResult = {
    try {
      val result = os.proc(req.config.cmd).call(
        cwd = os.Path(req.config.cwd),
        env = req.config.env,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        mergeErrIntoOut = req.config.mergeErrIntoOut,
        timeout = req.config.timeoutMillis,
        propagateEnv = req.config.propagateEnv,
        check = false
      )
      SubprocessResult(result.exitCode)
    } catch {
      case _: Exception => SubprocessResult(1)
    }
  }
}
