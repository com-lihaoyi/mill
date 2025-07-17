package mill.rpc

import mill.api.daemon.Logger
import upickle.default.{Reader, Writer}

import scala.util.control.NonFatal

trait MillRpcMessage {
  type Response

  given responseWriter: Writer[Response] = compiletime.deferred
}
object MillRpcMessage {
  /** Messages that do not have a meaningful response. */
  trait NoResponse extends MillRpcMessage {
    override type Response = Unit
  }
}

/** One way channel for sending RPC messages. */
trait MillRpcChannel[Input <: MillRpcMessage] {
  def apply(input: Input): input.Response
}

trait MillRpcServer[
    Initialize,
    ClientToServer <: MillRpcMessage,
    ServerToClient <: MillRpcMessage
] {

  /**
   * @param initialize First initialization message the client sends when it connects.
   * @param serverToClient A channel to the client.
   */
  def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer]
}

/** Default implementation for the [[MillRpcServer]]. */
trait MillRpcServerImpl[
    Initialize: Reader,
    ClientToServer <: MillRpcMessage: Reader,
    ServerToClient <: MillRpcMessage: Writer
] extends MillRpcServer[Initialize, ClientToServer, ServerToClient] {
  private var requestNumber = 0L

  def main(args: Array[String]): Unit = {
    logLocal("Initializing Mill RPC server... Waiting for the `initialize` message.")
    val initializeMessage = readAndTryToParse[Initialize]()
    val serverToClient = createServerToClientChannel()
    val clientLogger = RpcLogger.create(message => sendToClient(MillRpcServerToClient.Log(message)))
    val channel = initialize(initializeMessage.parsed, clientLogger, serverToClient)
    logLocal("Initialized, waiting for messages.")

    while (true) {
      val metadata = readAndTryToParse[ClientToServer]()
      val message = metadata.parsed
      import message.responseWriter
      sendToClient(MillRpcServerToClient.AskReceived(metadata.requestNumber))

      val result =
        try Right(channel(message))
        catch { case NonFatal(err) => Left(err.toString) }

      sendToClient(MillRpcServerToClient.Response(metadata.requestNumber, result))
    }
  }

  private def readAndTryToParse[A: Reader]() = {
    requestNumber += 1
    Option(Console.in.readLine()) match {
      case None =>
        logLocal("stdin broken, exiting process")
        sys.exit(0)

      case Some(line) =>
        logLocal(s"Received: $line")
        val parsed = upickle.default.read(line)
        logLocal(s"Parsed: ${pprint.apply(parsed)}")
        (requestNumber = requestNumber, parsed = parsed)
    }
  }

  private def sendToClient[A: Writer](message: MillRpcServerToClient[A]): Unit = {
    logLocal(s"Serializing message to be sent: ${pprint.apply(message)}")
    val serialized = upickle.default.write(message)
    logLocal(s"Sending: $serialized")
    println(serialized)
  }

  /** Logs a message locally in the RPC server. */
  private def logLocal(message: String): Unit = {
    System.err.println(s"[#$requestNumber] $message")
  }

  private def createServerToClientChannel(): MillRpcChannel[ServerToClient] =
    (msg: ServerToClient) => {
      requestNumber += 1
      sendToClient(MillRpcServerToClient.Ask(requestNumber, msg))
      ???
    }
}
