package mill.rpc

import upickle.default.{Reader, Writer}

/** Protocol messages that are sent from client to the server. */
enum MillRpcClientToServer[+Data] {

  /** Client is asking the server to do something. */
  case Ask(id: MillRpcRequestId, data: Data)

  /** Response to server's [[MillRpcServerToClient.Ask]] which either succeeded or failed. */
  case Response(id: MillRpcRequestId, data: Either[RpcThrowable, Data])
}

object MillRpcClientToServer {
  given reader[Data: Reader]: Reader[MillRpcClientToServer[Data]] = Reader.derived
  given writer[Data: Writer]: Writer[MillRpcClientToServer[Data]] = Writer.derived
}
