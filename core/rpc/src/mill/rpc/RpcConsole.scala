package mill.rpc

/** Messages for simulating a simple console via RPC. */
object RpcConsole {
  sealed trait Message derives upickle.default.ReadWriter
  object Message {
    case class Print(s: String) extends Message
    case object Flush extends Message
  }
}
