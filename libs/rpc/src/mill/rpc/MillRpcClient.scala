package mill.rpc

import mill.api.daemon.Logger
import pprint.TPrint
import upickle.{Reader, Writer}

import scala.util.Try

/** Connects and communicates with [[MillRpcServer]]. */
trait MillRpcClient[
    ClientToServer <: MillRpcChannel.Message,
    ServerToClient <: MillRpcChannel.Message
] extends AutoCloseable {
  def apply(input: ClientToServer): input.Response

  /** Exchanges the [[ServerToClient]] message handler. */
  def withServerToClientHandler(handler: MillRpcChannel[ServerToClient]): Unit
}
object MillRpcClient {
  def create[
      Initialize: Writer,
      ClientToServer <: MillRpcChannel.Message: Writer,
      ServerToClient <: MillRpcChannel.Message: Reader
  ](
      initialize: Initialize,
      wireTransport: MillRpcWireTransport,
      log: Logger.Actions,
      stdout: RpcConsole.Message => Unit = RpcConsole.stdoutHandler,
      stderr: RpcConsole.Message => Unit = RpcConsole.stderrHandler
  )(serverMessageHandler: MillRpcChannel[ServerToClient])
      : MillRpcClient[ClientToServer, ServerToClient] = {
    @volatile var currentServerMessageHandler = serverMessageHandler

    def logDebug(msg: String): Unit = log.debug(s"[RPC:${wireTransport.name}] $msg")

    def handleServerLog(msg: RpcLogger.Message): Unit = msg match {
      case RpcLogger.Message.Error(msg) => log.error(s"[RPC-SERVER:${wireTransport.name}] $msg")
      case RpcLogger.Message.Warn(msg) => log.warn(s"[RPC-SERVER:${wireTransport.name}] $msg")
      case RpcLogger.Message.Info(msg) => log.info(s"[RPC-SERVER:${wireTransport.name}] $msg")
      case RpcLogger.Message.Debug(msg) => log.debug(s"[RPC-SERVER:${wireTransport.name}] $msg")
      case RpcLogger.Message.Ticker(msg) => log.ticker(s"[RPC-SERVER:${wireTransport.name}] $msg")
    }

    def awaitForResponse[A: Reader]: A = {
      // When we send a request, server can send another request back at us, to get more data which is needed to
      // fulfill our request.
      var responseReceived = Option.empty[A]
      while (responseReceived.isEmpty) {
        // We parse into generic JSON value first because the message can be either `Response` or other `Ask` and if
        // we try to parse into `MillRpcServerToClient[A]` we will get an error if it's an `Ask`.
        wireTransport.readAndTryToParse[MillRpcServerToClient[ujson.Value]](logDebug) match {
          case None =>
            throw new IllegalStateException(
              s"RPC wire has broken (${wireTransport.name}). The server probably crashed."
            )
          case Some(MillRpcServerToClient.Ask(dataJson)) =>
            val data = upickle.read[ServerToClient](dataJson)
            handleServerMessage(data)
          case Some(MillRpcServerToClient.Response(either)) =>
            either match {
              case Left(err) => throw err
              case Right(responseJson) => responseReceived = Some(upickle.read[A](responseJson))
            }
          case Some(MillRpcServerToClient.Log(msg)) => handleServerLog(msg)
          case Some(MillRpcServerToClient.Stdout(msg)) => stdout(msg)
          case Some(MillRpcServerToClient.Stderr(msg)) => stderr(msg)
        }
      }

      responseReceived match {
        case Some(value) => value
        case None => throw new IllegalStateException("this should never happen")
      }
    }

    def handleServerMessage(msg: ServerToClient): Unit = {
      val response =
        Try(currentServerMessageHandler(msg)).toEither.left.map(RpcThrowable.apply)
      wireTransport.writeSerialized(MillRpcClientToServer.Response(response), logDebug)
    }

    wireTransport.writeSerialized(initialize, logDebug)

    new MillRpcClient[ClientToServer, ServerToClient] {
      override def apply(msg: ClientToServer): msg.Response = {
        wireTransport.writeSerialized(MillRpcClientToServer.Ask(msg), logDebug)
        awaitForResponse[msg.Response](using msg.responseRw)
      }

      override def withServerToClientHandler(handler: MillRpcChannel[ServerToClient]): Unit = {
        currentServerMessageHandler = handler
      }

      override def close(): Unit = {
        wireTransport.close()
      }
    }
  }
}
