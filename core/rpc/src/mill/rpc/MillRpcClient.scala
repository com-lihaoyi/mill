package mill.rpc

import upickle.default.{Reader, Writer}
import os.SubProcess

/** Connects and communicates with [[MillRpcServer]]. */
object MillRpcClient {
  def create[Initialize : Writer, ClientToServer <: MillRpcMessage : Writer, ServerToClient <: MillRpcMessage : Reader](
      initialize: Initialize,
      wireTransport: MillRpcWireTransport,
      handleServerMessage: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer] = {
    wireTransport.write(upickle.default.write(initialize))

    msg => {
      wireTransport.write(upickle.default.write(MillRpcClientToServer.Ask(msg)))
      
      // After sending the message, wait for the server to respond with the request number.
      wireTransport.read()
      
      handleServerMessage(MillRpcClientToServer.Response(requestNumber))
    }
  }
}
