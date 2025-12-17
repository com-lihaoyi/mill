package mill.rpc

/** One way channel for sending RPC messages. */
trait MillRpcChannel[Input <: MillRpcChannel.Message] {
  def apply(input: Input): input.Response
}
object MillRpcChannel {

  import upickle.ReadWriter

  trait Message {
    type Response

    given responseRw: ReadWriter[Response] = compiletime.deferred
  }

}
