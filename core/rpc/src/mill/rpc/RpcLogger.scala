package mill.rpc

import mill.api.daemon.Logger

/** Logs messages by sending them from the server to the client via an RPC. */
object RpcLogger {
  sealed trait Message extends MillRpcMessage derives upickle.default.ReadWriter
  object Message {
    case class Info(s: String) extends Message with MillRpcMessage.NoResponse
    case class Debug(s: String) extends Message with MillRpcMessage.NoResponse
    case class Warn(s: String) extends Message with MillRpcMessage.NoResponse
    case class Error(s: String) extends Message with MillRpcMessage.NoResponse
    case class Ticker(s: String) extends Message with MillRpcMessage.NoResponse
  }

  def create(send: Message => Unit): Logger.Actions = new {
    override def info(s: String): Unit = send(Message.Info(s))
    override def debug(s: String): Unit = send(Message.Debug(s))
    override def warn(s: String): Unit = send(Message.Warn(s))
    override def error(s: String): Unit = send(Message.Error(s))
    override def ticker(s: String): Unit = send(Message.Ticker(s))
  }
}
