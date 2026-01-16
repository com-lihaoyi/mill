package mill.launcher

import mill.api.daemon.Logger
import mill.rpc.*
import upickle.ReadWriter

import java.io.{BufferedReader, PrintStream}

/**
 * Shared RPC message types for communication between Mill launcher and daemon.
 * These types are used by both DaemonRpcServer (in runner/daemon) and the
 * client code (in runner/launcher).
 */
object DaemonRpc {
  /**
   * Initialization data sent by the launcher when connecting.
   * Mirrors the old ClientInitData.
   */
  case class Initialize(
      interactive: Boolean,
      clientMillVersion: String,
      clientJavaVersion: String,
      args: Array[String],
      env: Map[String, String],
      userSpecifiedProperties: Map[String, String]
  ) derives ReadWriter

  /**
   * Messages from launcher (client) to daemon (server).
   */
  sealed trait ClientToServer extends MillRpcChannel.Message derives ReadWriter

  object ClientToServer {
    /**
     * Request to run a command.
     */
    case class RunCommand() extends ClientToServer {
      type Response = RunCommandResult
    }
  }

  /**
   * Result of running a command.
   */
  case class RunCommandResult(
      exitCode: Int,
      metadata: String = ""
  ) derives ReadWriter

  /**
   * Messages from daemon (server) to launcher (client).
   */
  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter

  object ServerToClient {
    // Currently no server-to-client messages needed beyond stdout/stderr/log
    // which are handled by the MillRpcServer base class.
    // We need at least one subclass for the sealed trait to derive ReadWriter.
    case class Ping() extends ServerToClient {
      type Response = Unit
    }
  }

  /**
   * Create an RPC client for communicating with the Mill daemon.
   *
   * @param initialize the initialization data to send to the daemon
   * @param serverToClient input stream from the daemon
   * @param clientToServer output stream to the daemon
   * @param stdout handler for stdout messages from the daemon
   * @param stderr handler for stderr messages from the daemon
   * @return an RPC client that can be used to send commands to the daemon
   */
  def createClient(
      initialize: Initialize,
      serverToClient: BufferedReader,
      clientToServer: PrintStream,
      stdout: RpcConsole.Message => Unit = RpcConsole.stdoutHandler,
      stderr: RpcConsole.Message => Unit = RpcConsole.stderrHandler
  ): MillRpcClient[ClientToServer, ServerToClient] = {
    mill.constants.DebugLog.println(s"DaemonRpc.createClient: creating transport")

    val transport = MillRpcWireTransport(
      name = "DaemonRpcClient",
      serverToClient = serverToClient,
      clientToServer = clientToServer,
      writeSynchronizer = new Object
    )

    mill.constants.DebugLog.println(s"DaemonRpc.createClient: creating client with init=$initialize")

    val log = new Logger.Actions {
      override def error(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:error] $msg")
      override def warn(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:warn] $msg")
      override def info(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:info] $msg")
      override def debug(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:debug] $msg")
      override def ticker(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:ticker] $msg")
    }

    // Handler for server-to-client messages (currently unused but required by the API)
    val serverMessageHandler = new MillRpcChannel[ServerToClient] {
      override def apply(input: ServerToClient): input.Response = {
        mill.constants.DebugLog.println(s"DaemonRpc: received server message: $input")
        input match {
          case _: ServerToClient.Ping => ().asInstanceOf[input.Response]
        }
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
}
