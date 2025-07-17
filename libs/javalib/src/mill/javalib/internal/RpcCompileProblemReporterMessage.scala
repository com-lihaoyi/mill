package mill.javalib.internal

import mill.rpc.MillRpcMessage
import mill.api.JsonFormatters.*

sealed trait RpcCompileProblemReporterMessage extends MillRpcMessage derives upickle.default.ReadWriter
object RpcCompileProblemReporterMessage {
  case object Start extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case class LogError(problem: RpcProblem) extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case class LogWarning(problem: RpcProblem) extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case class LogInfo(problem: RpcProblem) extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case class FileVisited(file: os.Path) extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case object PrintSummary extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case object Finish extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
  case class NotifyProgress(percentage: Long, total: Long) extends RpcCompileProblemReporterMessage with MillRpcMessage.NoResponse
}
