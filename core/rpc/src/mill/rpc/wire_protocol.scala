package mill.rpc

import upickle.default.{Reader, Writer}

/** Protocol messages that are sent from client to the server. */
enum MillRpcClientToServer[+Data] {

  /**
   * Client is asking the server to do something. Server will immediately respond with
   * [[MillRpcServerToClient.AskReceived]] to this.
   *
   * @param id the request id client has assigned to this message.
   */
  case Ask(id: MillRpcRequestId, data: Data)

  /** Response to server's [[MillRpcServerToClient.Ask]] which either succeeded or failed. */
  case Response(id: MillRpcRequestId, data: Either[RpcThrowable, Data])
}
object MillRpcClientToServer {
  given reader[Data: Reader]: Reader[MillRpcClientToServer[Data]] = Reader.derived
  given writer[Data: Writer]: Writer[MillRpcClientToServer[Data]] = Writer.derived
}

/** Protocol messages that are sent from server to the client. */
enum MillRpcServerToClient[+Data] {
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
}
object MillRpcServerToClient {
  given reader[Data: Reader]: Reader[MillRpcServerToClient[Data]] = Reader.derived
  given writer[Data: Writer]: Writer[MillRpcServerToClient[Data]] = Writer.derived
}

trait MillRpcWireTransport extends AutoCloseable {
  /** Human-readable name of the wire. */
  def name: String

  /** Reads one raw message from the wire. */
  def read(): Option[String]

  /** Helper that reads a message from the wire and tries to parse it, logging along the way. */
  def readAndTryToParse[A: Reader](log: String => Unit): Option[A] = {
    read() match {
      case None =>
        log("Transport wire broken.")

      case Some(line) =>
        log(s"Received: $line")
        val parsed = upickle.default.read(line)
        log(s"Parsed: ${pprint.apply(parsed)}")
        parsed
    }
  }

  /** Writes one raw message to the wire. */
  def write(message: String): Unit

  /** Helper that writes a message to the wire, logging along the way. */
  def writeSerialized[A: Writer](message: A, log: String => Unit): Unit = {
    log(s"Serializing message to be sent: ${pprint.apply(message)}")
    val serialized = upickle.default.write(message)
    log(s"Sending: $serialized")
    write(serialized)
  }
}
object MillRpcWireTransport {
  object ViaStdinAndStdout extends MillRpcWireTransport {
    def name: String = "stdin/out"

    def read(): Option[String] = Option(Console.in.readLine())
    def write(message: String): Unit = println(message)

    override def close(): Unit = {
      // Do nothing
    }
  }

  case class ViaStdinAndStdoutOfSubprocess(subprocess: os.SubProcess) extends MillRpcWireTransport {
    def name: String = s"stdin/out of subprocess (PID ${subprocess.wrapped.pid()})"

    def read(): Option[String] = Option(subprocess.stdout.readLine())
    def write(message: String): Unit = subprocess.stdin.writeLine(message)
    
    override def close(): Unit = {
      subprocess.close()
    }
  }
}
