package mill.rpc

import pprint.TPrint
import upickle.default.ReadWriter

trait MillRpcMessage {
  type Response

  given responseRw: ReadWriter[Response] = compiletime.deferred
  given responseTypeName: TPrint[Response] = compiletime.deferred
}
object MillRpcMessage {
  /** Messages that do not have a meaningful response. */
  trait NoResponse extends MillRpcMessage {
    override type Response = Unit
  }
}
