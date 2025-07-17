package mill.rpc

import upickle.default.{Reader, Writer}

/** Protocol messages that are sent from client to the server. */
enum MillRpcClientToServer[+Data] {
  /** Client is asking the server to do something. Server will immediately respond with
   * [[MillRpcServerToClient.AskReceived]] to this. */
  case Ask(data: Data)

  /** Response to server's [[MillRpcServerToClient.Ask]] which either succeeded or failed. */
  case Response(requestNo: Long, data: Either[String, Data])
}
object MillRpcClientToServer {
  given reader[Data: Reader]: Reader[MillRpcClientToServer[Data]] = Reader.derived
  given writer[Data: Writer]: Writer[MillRpcClientToServer[Data]] = Writer.derived
}

/** Protocol messages that are sent from server to the client. */
enum MillRpcServerToClient[+Data] {

  /** Acknowledgement that a [[MillRpcClientToServer.Ask]] was received and a request number has been given to it. */
  case AskReceived(requestNo: Long)

  /** Response to a [[MillRpcClientToServer.Ask]] which either succeeded or failed. */
  case Response(requestNo: Long, data: Either[String, Data])

  /** Server is asking the client to do something. */
  case Ask(requestNo: Long, data: Data)

  /** We want to send a message to be logged in the client. */
  case Log(message: RpcLogger.Message)
}
object MillRpcServerToClient {
  given reader[Data: Reader]: Reader[MillRpcServerToClient[Data]] = Reader.derived
  given writer[Data: Writer]: Writer[MillRpcServerToClient[Data]] = Writer.derived
}

trait MillRpcWireTransport {
  /** Reads one raw message from the wire. */
  def read(): Option[String]
  
  /** Writes one raw message to the wire. */
  def write(message: String): Unit
}
object MillRpcWireTransport {
  object ViaStdinAndStdout extends MillRpcWireTransport {
    def read(): Option[String] = Option(Console.in.readLine())
    def write(message: String): Unit = println(message)
  }
  
  case class ViaStdinAndStdoutOfSubprocess(subprocess: os.SubProcess) extends MillRpcWireTransport {
    def read(): Option[String] = Option(subprocess.stdout.readLine())
    def write(message: String): Unit = subprocess.stdin.writeLine(message)
  }
}