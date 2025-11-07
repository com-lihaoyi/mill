package mill.javalib.internal

import mill.api.JsonFormatters.*

sealed trait RpcProblemMessage derives upickle.ReadWriter
object RpcProblemMessage {
  case object Start extends RpcProblemMessage
  case class LogError(problem: RpcProblem) extends RpcProblemMessage
  case class LogWarning(problem: RpcProblem) extends RpcProblemMessage
  case class LogInfo(problem: RpcProblem) extends RpcProblemMessage
  case class FileVisited(file: os.Path) extends RpcProblemMessage
  case object PrintSummary extends RpcProblemMessage
  case object Finish extends RpcProblemMessage
  case class NotifyProgress(progress: Long, total: Long) extends RpcProblemMessage
}
