package mill.rpc

import mill.api.daemon.Logger

/** Logs messages by sending them from the server to the client via an RPC. */
object RpcLogger {
  enum Message derives upickle.ReadWriter {
    case Info(s: String)
    case Debug(s: String)
    case Warn(s: String)
    case Error(s: String)
    case Ticker(s: String)
  }

  def create(send: Message => Unit): Logger.Actions = new {
    override def info(s: String): Unit = send(Message.Info(s))
    override def debug(s: String): Unit = send(Message.Debug(s))
    override def warn(s: String): Unit = send(Message.Warn(s))
    override def error(s: String): Unit = send(Message.Error(s))
    override def ticker(s: String): Unit = send(Message.Ticker(s))
  }
}
