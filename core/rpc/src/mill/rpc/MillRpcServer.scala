package mill.rpc

import mill.api.daemon.Logger
import upickle.default.{Reader, Writer}

import scala.annotation.unused
import scala.util.Try
import scala.util.control.NonFatal

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
](wireTransport: MillRpcWireTransport)
    extends MillRpcServer[Initialize, ClientToServer, ServerToClient] {
  @volatile private var currentRequestId = Option.empty[MillRpcRequestId]
  @volatile private var initializedOnClientMessage = Option.empty[MillRpcChannel[ClientToServer]]

  private val clientLogger = RpcLogger.create(message => sendToClient(MillRpcServerToClient.Log(message)))

  def main(@unused args: Array[String]): Unit = {
    logLocal("Initializing Mill RPC server... Waiting for the `initialize` message.")
    val onClientMessage = {
      val initializeMessage = readAndTryToParse[Initialize]()
      val serverToClient = createServerToClientChannel()
      initialize(initializeMessage, clientLogger, serverToClient)
    }
    initializedOnClientMessage = Some(onClientMessage)
    logLocal("Initialized, waiting for messages.")

    while (true) {
      val clientToServerMsg = readAndTryToParse[MillRpcClientToServer[ClientToServer]]()

      clientToServerMsg match {
        case MillRpcClientToServer.Ask(requestId, message) =>
          onAsk(requestId)(requestId => onClientMessage(requestId, message))

        case MillRpcClientToServer.Response(requestId, data) =>
          val msg =
            s"Received response, however we weren't expecting any, (request id = $requestId), " +
              s"ignoring: ${pprint.apply(data)}"
          logLocal(msg)
          clientLogger.warn(msg)
      }
    }
  }

  private def onAsk[Response : Writer](requestId: MillRpcRequestId)(run: MillRpcRequestId => Response): Unit = {
    withRequestId(requestId) { requestId =>
      val result = Try(run(requestId)).toEither.left.map(RpcThrowable(_))
      sendToClient(MillRpcServerToClient.Response(requestId, result))
    }
  }

  private def waitForResponse[R:Reader](
    clientToServer: MillRpcChannel[ClientToServer],
    awaitingResponseTo: MillRpcRequestId
  ): R = {
    var responseReceived = Option.empty[R]

    while (responseReceived.isEmpty) {
      val clientToServerMsg = readAndTryToParse[MillRpcClientToServer[R]]()

      clientToServerMsg match {
        case MillRpcClientToServer.Ask(requestId, message) =>
          val askMessage = message.asInstanceOf[ClientToServer]
          onAsk(requestId)(requestId => clientToServer(requestId, askMessage))(using askMessage.responseRw)

        case MillRpcClientToServer.Response(requestId, data) =>
          if (requestId == awaitingResponseTo) {
            data match {
              case Left(err) => throw err.toThrowable
              case Right(response) => responseReceived = Some(response)
            }
          }
          else {
            val msg =
              s"Received response with the unknown wrong request id ($requestId), while we are expecting " +
                s"response for request id ($awaitingResponseTo), ignoring: ${pprint.apply(data)}"
            logLocal(msg)
            clientLogger.warn(msg)
          }
      }
    }

    responseReceived.getOrElse(throw new IllegalStateException("This should never happen."))
  }

  private def withRequestId[A](requestId: MillRpcRequestId)(f: MillRpcRequestId => A): A = {
    val previousRequestId = currentRequestId
    currentRequestId = Some(requestId)
    try f(requestId)
    finally currentRequestId = previousRequestId
  }

  private def readAndTryToParse[A: Reader](): A = {
    wireTransport.readAndTryToParse(logLocal) match {
      case None => sys.exit(0)
      case Some(parsed) => parsed
    }
  }

  private def sendToClient[A: Writer](message: MillRpcServerToClient[A]): Unit =
    wireTransport.writeSerialized(message, logLocal)

  /** Logs a message locally in the RPC server. */
  private def logLocal(message: String): Unit = {
    System.err.println(s"$message")
  }

  private def createServerToClientChannel(): MillRpcChannel[ServerToClient] = {
    (msg: ServerToClient) => {
      val clientToServer = initializedOnClientMessage.getOrElse(throw new IllegalStateException(
        "Client to server channel should have been initialized, this is a bug in the RPC implementation."
      ))

      val requestId = currentRequestId.getOrElse(throw new IllegalStateException(
        "The current protocol implementation does not support server sending messages to the client " +
          "without receiving a request from the client first."
      ))

      withRequestId(requestId.requestStartedFromServer) { requestId =>
        sendToClient(MillRpcServerToClient.Ask(requestId, msg))
        waitForResponse[msg.Response](clientToServer, requestId)
      }
    }
  }
}
