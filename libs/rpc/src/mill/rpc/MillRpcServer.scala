package mill.rpc

import mill.api.daemon.Logger
import pprint.TPrint
import upickle.{Reader, Writer}

import scala.util.control.NonFatal

/** Default implementation for the [[MillRpcServer]]. */
trait MillRpcServer[
    Initialize: Reader,
    ClientToServer <: MillRpcChannel.Message: Reader,
    ServerToClient <: MillRpcChannel.Message: Writer
](serverName: String, wireTransport: MillRpcWireTransport, writeToLocalLog: String => Unit) {
  @volatile private var initializedOnClientMessage = Option.empty[MillRpcChannel[ClientToServer]]

  private val clientLogger =
    RpcLogger.create(message => sendToClient(MillRpcServerToClient.Log(message)))

  val clientStdout: RpcConsole =
    RpcConsole.create(msg => sendToClient(MillRpcServerToClient.Stdout(msg)))
  val clientStderr: RpcConsole =
    RpcConsole.create(msg => sendToClient(MillRpcServerToClient.Stderr(msg)))

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

  def run(): Unit = {
    logLocal("Initializing Mill RPC server... Waiting for the `initialize` message.")
    val onClientMessage = {
      val initializeMessage = readAndTryToParse[Initialize]() match {
        case Some(value) => value
        case None => return
      }
      val serverToClient = createServerToClientChannel()
      initialize(
        initializeMessage,
        clientLogger,
        clientStdout = clientStdout,
        clientStderr = clientStderr,
        serverToClient
      )
    }
    initializedOnClientMessage = Some(onClientMessage)
    logLocal("Initialized, waiting for messages.")

    var continue = true
    while (continue) {
      readAndTryToParse[MillRpcClientToServer[ClientToServer]]() match {
        case None => continue = false
        case Some(MillRpcClientToServer.Ask(message)) =>
          continue = onAsk()(() => onClientMessage(message))
      }
    }
  }

  private def onAsk[Response: Writer]()(
      run: () => Response
  ): Boolean = {
    val result =
      try Right(run())
      catch {
        case _: InterruptedException => return false
        case NonFatal(e) => Left(RpcThrowable(e))
      }

    sendToClient(MillRpcServerToClient.Response(result))
    true
  }

  private def waitForResponse[R: Reader](
      clientToServer: MillRpcChannel[ClientToServer]
  ): R = {
    var responseReceived = Option.empty[R]

    while (responseReceived.isEmpty) {
      val clientToServerMsg = readAndTryToParse[MillRpcClientToServer[R]]().getOrElse(
        throw new InterruptedException(
          s"Transport wire broken while waiting for response to request."
        )
      )

      clientToServerMsg match {
        case MillRpcClientToServer.Ask(message) =>
          val askMessage = message.asInstanceOf[ClientToServer]
          onAsk()(() => clientToServer(askMessage))(using
            askMessage.responseRw
          )

        case MillRpcClientToServer.Response(data) =>
          data match {
            case Left(err) => throw err
            case Right(response) => responseReceived = Some(response)
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
    writeToLocalLog(s"[$serverName] $message")
  }

  private def createServerToClientChannel(): MillRpcChannel[ServerToClient] = {
    (msg: ServerToClient) =>
      {
        val clientToServer = initializedOnClientMessage.getOrElse(throw new IllegalStateException(
          "Client to server channel should have been initialized, this is a bug in the RPC implementation."
        ))

        sendToClient(MillRpcServerToClient.Ask(msg))
        waitForResponse[msg.Response](clientToServer)
      }
  }
}
