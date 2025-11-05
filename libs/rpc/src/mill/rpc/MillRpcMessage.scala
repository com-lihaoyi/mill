package mill.rpc

import upickle.ReadWriter

trait MillRpcMessage {
  type Response

  given responseRw: ReadWriter[Response] = compiletime.deferred
}
