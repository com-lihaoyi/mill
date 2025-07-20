package mill.rpc

import mill.api.daemon.Logger
import os.SubProcess
import upickle.default.{Reader, Writer}

import scala.util.Try

/** Connects and communicates with [[MillRpcServer]]. */
trait MillRpcClient[ClientToServer <: MillRpcMessage] extends MillRpcChannel[ClientToServer],
      AutoCloseable
object MillRpcClient {
  def create[
      Initialize: Writer,
      ClientToServer <: MillRpcMessage: Writer,
      ServerToClient <: MillRpcMessage: Reader
  ](
      initialize: Initialize,
      wireTransport: MillRpcWireTransport,
      log: Logger.Actions
  )(serverMessageHandler: MillRpcChannel[ServerToClient]): MillRpcClient[ClientToServer] = {
    def logWarn(msg: String): Unit = log.warn(s"[RPC] $msg")
    def logDebug(msg: String): Unit = log.debug(s"[RPC] $msg")

    def withRequestId[A](id: MillRpcRequestId)(f: MillRpcRequestId => A): A = {
      currentRequestId = id
      try f(id)
      finally currentRequestId = currentRequestId.requestFinished
    }

    def handleServerLog(msg: RpcLogger.Message): Unit = msg match {
      case RpcLogger.Message.Error(msg) => log.error(s"[RPC-SERVER] $msg")
      case RpcLogger.Message.Warn(msg) => log.warn(s"[RPC-SERVER] $msg")
      case RpcLogger.Message.Info(msg) => log.info(s"[RPC-SERVER] $msg")
      case RpcLogger.Message.Debug(msg) => log.debug(s"[RPC-SERVER] $msg")
      case RpcLogger.Message.Ticker(msg) => log.ticker(s"[RPC-SERVER] $msg")
    }

    def awaitForResponse[R: Reader](requestId: MillRpcRequestId): R = {
      // When we send a request, server can send another request back at us, to get more data which is needed to
      // fulfill our request.
      var responseReceived = Option.empty[R]
      while (responseReceived.isEmpty) {
        wireTransport.readAndTryToParse[MillRpcServerToClient[R]](logDebug) match {
          case None =>
            throw new IllegalStateException(
              s"RPC wire has broken (${wireTransport.name}). The server probably crashed."
            )
          case Some(MillRpcServerToClient.Log(msg)) =>
            handleServerLog(msg)
          case Some(MillRpcServerToClient.Ask(id, data)) =>
            handleServerMessage(id, data.asInstanceOf[ServerToClient])
          case Some(MillRpcServerToClient.Response(`requestId`, either)) =>
            either match {
              case Left(err) =>
                throw err.toThrowable
              case Right(response) =>
                responseReceived = Some(response)
            }
          case Some(MillRpcServerToClient.Response(reqId, either)) =>
            logWarn(
              s"Received response with the unknown wrong request id ($reqId), ignoring: ${pprint.apply(either)}"
            )
        }
      }

      responseReceived match {
        case Some(value) => value
        case None => throw new IllegalStateException("this should never happen")
      }
    }

    def handleServerMessage(requestId: MillRpcRequestId, msg: ServerToClient): Unit = {
      withRequestId(requestId) { requestId =>
        val response =
          Try(serverMessageHandler(requestId, msg)).toEither.left.map(RpcThrowable.apply)
        wireTransport.writeSerialized(MillRpcClientToServer.Response(requestId, response), logDebug)
      }
    }

    wireTransport.writeSerialized(initialize, logDebug)

    var currentRequestId = MillRpcRequestId.initialForClient
    new {
      override def apply(msg: ClientToServer): msg.Response = {
        withRequestId(currentRequestId.requestStartedFromClient) { requestId =>
          wireTransport.writeSerialized(MillRpcClientToServer.Ask(requestId, msg), logDebug)
          awaitForResponse[msg.Response](requestId)(using msg.responseRw)
        }
      }

      override def close(): Unit = {
        wireTransport.close()
      }
    }
  }
}
