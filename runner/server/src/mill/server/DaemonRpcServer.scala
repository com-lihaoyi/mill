package mill.server

import mill.api.daemon.Logger
import mill.launcher.DaemonRpc
import mill.rpc.*

import java.io.PrintStream

/**
 * RPC server for the Mill daemon. Handles requests from the launcher to run commands.
 */
class DaemonRpcServer(
    serverName: String,
    transport: MillRpcWireTransport,
    setIdle: Server.SetIdle,
    writeToLocalLog: String => Unit,
    runCommand: DaemonRpcServer.RunCommandHandler
) extends MillRpcServer[
      DaemonRpc.Initialize,
      DaemonRpc.ClientToServer,
      DaemonRpc.ServerToClient
    ](serverName, transport, writeToLocalLog) {

  override def initialize(
      init: DaemonRpc.Initialize,
      log: Logger.Actions,
      clientStdout: RpcConsole,
      clientStderr: RpcConsole,
      serverToClient: MillRpcChannel[DaemonRpc.ServerToClient]
  ): MillRpcChannel[DaemonRpc.ClientToServer] = {
    mill.constants.DebugLog.println(s"DaemonRpcServer.initialize: $init")

    new MillRpcChannel[DaemonRpc.ClientToServer] {
      override def apply(input: DaemonRpc.ClientToServer): input.Response = {
        mill.constants.DebugLog.println(s"DaemonRpcServer received request: $input")
        input match {
          case req: DaemonRpc.ClientToServer.RunCommand =>
            setIdle.doWork {
              val result = runCommand(
                init,
                req,
                PrintStream(clientStdout.asStream),
                PrintStream(clientStderr.asStream),
                setIdle
              )
              mill.constants.DebugLog.println(s"DaemonRpcServer RunCommand result: $result")
              result.asInstanceOf[input.Response]
            }
        }
      }
    }
  }
}

object DaemonRpcServer {
  /**
   * Handler for running commands. Returns exit code and optional metadata (e.g., skipped interactive tasks).
   */
  type RunCommandHandler = (
      DaemonRpc.Initialize,
      DaemonRpc.ClientToServer.RunCommand,
      PrintStream, // stdout
      PrintStream, // stderr
      Server.SetIdle
  ) => DaemonRpc.ClientToServer.RunCommand#Response
}
