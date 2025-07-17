package mill.javalib.internal

import mill.api.JsonFormatters.*
import mill.rpc.MillRpcMessage

sealed trait RpcCompileProblemReporterMessage extends MillRpcMessage, MillRpcMessage.NoResponse
    derives upickle.default.ReadWriter
object RpcCompileProblemReporterMessage {
  case object Start extends RpcCompileProblemReporterMessage
  case class LogError(problem: RpcProblem) extends RpcCompileProblemReporterMessage
  case class LogWarning(problem: RpcProblem) extends RpcCompileProblemReporterMessage
  case class LogInfo(problem: RpcProblem) extends RpcCompileProblemReporterMessage
  case class FileVisited(file: os.Path) extends RpcCompileProblemReporterMessage
  case object PrintSummary extends RpcCompileProblemReporterMessage
  case object Finish extends RpcCompileProblemReporterMessage
  case class NotifyProgress(percentage: Long, total: Long) extends RpcCompileProblemReporterMessage
}
