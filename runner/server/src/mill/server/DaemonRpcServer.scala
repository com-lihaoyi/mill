package mill.server

import mill.api.daemon.Logger
import mill.launcher.DaemonRpc
import mill.rpc.*

import java.io.PrintStream

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
  ): MillRpcChannel[DaemonRpc.ClientToServer] =
    new MillRpcChannel[DaemonRpc.ClientToServer] {
      override def apply(input: DaemonRpc.ClientToServer): input.Response = input match {
        case req: DaemonRpc.ClientToServer.RunCommand =>
          setIdle.doWork {
            runCommand(
              init,
              req,
              PrintStream(clientStdout.asStream),
              PrintStream(clientStderr.asStream),
              setIdle,
              serverToClient
            ).asInstanceOf[input.Response]
          }
      }
    }
}

object DaemonRpcServer {
  type RunCommandHandler = (
      DaemonRpc.Initialize,
      DaemonRpc.ClientToServer.RunCommand,
      PrintStream,
      PrintStream,
      Server.SetIdle,
      MillRpcChannel[DaemonRpc.ServerToClient]
  ) => DaemonRpc.ClientToServer.RunCommand#Response
}
