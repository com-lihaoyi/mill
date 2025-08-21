package mill.rpc

/** One way channel for sending RPC messages. */
trait MillRpcChannel[Input <: MillRpcMessage] {
  def apply(requestId: MillRpcRequestId, input: Input): input.Response
}
