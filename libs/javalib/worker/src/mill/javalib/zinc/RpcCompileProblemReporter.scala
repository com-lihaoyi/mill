package mill.javalib.zinc

import mill.api.daemon.internal.{CompileProblemReporter, Problem}
import mill.javalib.internal.{RpcCompileProblemReporterMessage, RpcProblem}

import java.nio.file.Path

/** A [[CompileProblemReporter]] that sends problems to the Mill server over RPC. */
class RpcCompileProblemReporter(
    override val maxErrors: Int,
    send: RpcCompileProblemReporterMessage => Unit
) extends CompileProblemReporter {
  override def start(): Unit = send(RpcCompileProblemReporterMessage.Start)

  override def logError(problem: Problem): Unit =
    send(RpcCompileProblemReporterMessage.LogError(RpcProblem(problem)))

  override def logWarning(problem: Problem): Unit =
    send(RpcCompileProblemReporterMessage.LogWarning(RpcProblem(problem)))

  override def logInfo(problem: Problem): Unit =
    send(RpcCompileProblemReporterMessage.LogInfo(RpcProblem(problem)))

  override def fileVisited(file: Path): Unit =
    send(RpcCompileProblemReporterMessage.FileVisited(os.Path(file)))

  override def printSummary(): Unit = send(RpcCompileProblemReporterMessage.PrintSummary)

  override def finish(): Unit = send(RpcCompileProblemReporterMessage.Finish)

  override def notifyProgress(progress: Long, total: Long): Unit =
    send(RpcCompileProblemReporterMessage.NotifyProgress(progress, total))
}
