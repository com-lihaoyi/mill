package mill.rpc

import upickle.default.Writer

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
