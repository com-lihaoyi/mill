package mill.rpc

import upickle.{Reader, Writer}

/** Protocol messages that are sent from client to the server. */
enum MillRpcClientToServer[+Data] derives Reader, Writer {

  /** Client is asking the server to do something. */
  case Ask(data: Data)

  /** Response to server's [[MillRpcServerToClient.Ask]] which either succeeded or failed. */
  case Response(data: Either[RpcThrowable, Data])
}
