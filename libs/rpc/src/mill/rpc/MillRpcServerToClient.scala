package mill.rpc

import upickle.{Reader, Writer}

/** Protocol messages that are sent from server to the client. */
enum MillRpcServerToClient[+Data] derives Reader, Writer {

  /** Response to a [[MillRpcClientToServer.Ask]] which either succeeded or failed. */
  case Response(id: MillRpcRequestId, data: Either[RpcThrowable, Data])

  /**
   * Server is asking the client to do something. Client should respond with [[MillRpcClientToServer.Response]].
   *
   * @param id the request id server has assigned to this message.
   */
  case Ask(id: MillRpcRequestId, data: Data)

  /** We want to send a message to be logged in the client. */
  case Log(message: RpcLogger.Message)

  /** We want to send a message to the client's stdout. */
  case Stdout(msg: RpcConsole.Message)

  /** We want to send a message to the client's stderr. */
  case Stderr(msg: RpcConsole.Message)
}
