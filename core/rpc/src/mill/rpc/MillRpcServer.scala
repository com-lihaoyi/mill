package mill.rpc

import mill.api.daemon.Logger
import pprint.TPrint
import upickle.default.{Reader, Writer}

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
  protected def initialize(
      initialize: Initialize,
      log: Logger.Actions,
      clientStdout: RpcConsole,
      clientStderr: RpcConsole,
      serverToClient: MillRpcChannel[ServerToClient]
  ): MillRpcChannel[ClientToServer]

  /** Starts listening for messages, blocking the thread. */
  def run(): Unit
}
object MillRpcServer {
  def create[
      Initialize: Reader,
      ClientToServer <: MillRpcMessage: Reader,
      ServerToClient <: MillRpcMessage: Writer
  ](
      wireTransport: MillRpcWireTransport
  )(initializer: (
      Initialize,
      Logger.Actions,
      RpcConsole,
      RpcConsole,
      MillRpcChannel[ServerToClient]
  ) => MillRpcChannel[ClientToServer]): MillRpcServer[Initialize, ClientToServer, ServerToClient] =
    new MillRpcServerImpl[Initialize, ClientToServer, ServerToClient](wireTransport) {
      override def initialize(
          initialize: Initialize,
          log: Logger.Actions,
          clientStdout: RpcConsole,
          clientStderr: RpcConsole,
          serverToClient: MillRpcChannel[ServerToClient]
      ): MillRpcChannel[ClientToServer] =
        initializer(initialize, log, clientStdout, clientStderr, serverToClient)
    }
}

/** Default implementation for the [[MillRpcServer]]. */
trait MillRpcServerImpl[
    Initialize: Reader,
    ClientToServer <: MillRpcMessage: Reader,
    ServerToClient <: MillRpcMessage: Writer
](wireTransport: MillRpcWireTransport)
    extends MillRpcServer[Initialize, ClientToServer, ServerToClient] {
  @volatile private var initializedOnClientMessage = Option.empty[MillRpcChannel[ClientToServer]]

  private val clientLogger =
    RpcLogger.create(message => sendToClient(MillRpcServerToClient.Log(message)))

  private val clientStdout = RpcConsole.create(msg => sendToClient(MillRpcServerToClient.Stdout(msg)))
  private val clientStderr = RpcConsole.create(msg => sendToClient(MillRpcServerToClient.Stderr(msg)))

  def run(): Unit = {
    logLocal("Initializing Mill RPC server... Waiting for the `initialize` message.")
    val onClientMessage = {
      val initializeMessage = readAndTryToParse[Initialize]() match {
        case Some(value) => value
        case None => return
      }
      val serverToClient = createServerToClientChannel()
      initialize(initializeMessage, clientLogger, clientStdout = clientStdout, clientStderr = clientStderr, serverToClient)
    }
    initializedOnClientMessage = Some(onClientMessage)
    logLocal("Initialized, waiting for messages.")

    var continue = true
    while (continue) {
      readAndTryToParse[MillRpcClientToServer[ClientToServer]]() match {
        case None => continue = false
        case Some(MillRpcClientToServer.Ask(requestId, message)) =>
          continue = onAsk(requestId)(requestId => onClientMessage(requestId, message))
        case Some(MillRpcClientToServer.Response(requestId, data)) =>
          val msg =
            s"Received response, however we weren't expecting any, (request id = $requestId), " +
              s"ignoring: ${pprint.apply(data)}"
          logLocal(msg)
          clientLogger.warn(msg)
      }
    }
  }

  private def onAsk[Response: Writer](requestId: MillRpcRequestId)(
      run: MillRpcRequestId => Response
  ): Boolean = {
    val result =
      try Right(run(requestId))
      catch {
        case _: InterruptedException => return false
        case NonFatal(e) => Left(RpcThrowable(e))
      }

    sendToClient(MillRpcServerToClient.Response(requestId, result))
    true
  }

  private def waitForResponse[R: Reader](
      clientToServer: MillRpcChannel[ClientToServer],
      awaitingResponseTo: MillRpcRequestId
  ): R = {
    var responseReceived = Option.empty[R]

    while (responseReceived.isEmpty) {
      val clientToServerMsg = readAndTryToParse[MillRpcClientToServer[R]]().getOrElse(
        throw new InterruptedException(s"Transport wire broken while waiting for response to request $awaitingResponseTo.")
      )

      clientToServerMsg match {
        case MillRpcClientToServer.Ask(requestId, message) =>
          val askMessage = message.asInstanceOf[ClientToServer]
          onAsk(requestId)(requestId => clientToServer(requestId, askMessage))(using
            askMessage.responseRw
          )

        case MillRpcClientToServer.Response(requestId, data) =>
          if (requestId == awaitingResponseTo) {
            data match {
              case Left(err) => throw err.toThrowable
              case Right(response) => responseReceived = Some(response)
            }
          } else {
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

  private def readAndTryToParse[A: Reader]()(using typeName: TPrint[A]): Option[A] =
    wireTransport.readAndTryToParse(logLocal)

  private def sendToClient[A: Writer](message: MillRpcServerToClient[A]): Unit =
    wireTransport.writeSerialized(message, logLocal)

  /** Logs a message locally in the RPC server. */
  private def logLocal(message: String): Unit = {
    System.err.println(s"$message")
  }

  private def createServerToClientChannel(): MillRpcChannel[ServerToClient] = {
    @volatile var lastClientRequestId = Option.empty[MillRpcRequestId]

    (clientRequestId: MillRpcRequestId, msg: ServerToClient) => {
      val clientToServer = initializedOnClientMessage.getOrElse(throw new IllegalStateException(
        "Client to server channel should have been initialized, this is a bug in the RPC implementation."
      ))

      val requestId = (lastClientRequestId match {
        case None => clientRequestId
        case Some(last) if last.requestFinished == clientRequestId => last
        case Some(_) => clientRequestId
      }).requestStartedFromServer
      lastClientRequestId = Some(requestId)
      sendToClient(MillRpcServerToClient.Ask(requestId, msg))
      waitForResponse[msg.Response](clientToServer, requestId)
    }
  }
}
