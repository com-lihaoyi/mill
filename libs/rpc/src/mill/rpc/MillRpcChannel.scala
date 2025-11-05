package mill.rpc

/** One way channel for sending RPC messages. */
trait MillRpcChannel[Input <: MillRpcMessage] {
  def apply(input: Input): input.Response
}
