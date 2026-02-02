package mill.rpc

import mill.api.JsonFormatters.Default.given
import mill.api.daemon.Result

/** Serialized failure info for RPC transport. */
case class RpcThrowable(
    exceptions: Seq[Result.Failure.ExceptionInfo]
) derives upickle.ReadWriter

object RpcThrowable {
  def fromThrowable(t: Throwable): RpcThrowable = {
    val failure = Result.Failure.fromException(t)
    val chain =
      if (failure.exception.nonEmpty) failure.exception
      else
        Seq(Result.Failure.ExceptionInfo(
          t.getClass.getName,
          t.getMessage,
          t.getStackTrace.toSeq
        ))
    RpcThrowable(chain)
  }
}
