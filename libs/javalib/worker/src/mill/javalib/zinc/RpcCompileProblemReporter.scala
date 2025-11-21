package mill.javalib.zinc

import mill.api.daemon.internal.{CompileProblemReporter, Problem}
import mill.javalib.internal.{RpcProblemMessage, RpcProblem}

import java.nio.file.Path

/** A [[CompileProblemReporter]] that sends problems to the Mill server over RPC. */
class RpcCompileProblemReporter(
    override val maxErrors: Int,
    send: RpcProblemMessage => Unit
) extends CompileProblemReporter {
  override def start(): Unit = send(RpcProblemMessage.Start)

  override def logError(problem: Problem): Unit =
    send(RpcProblemMessage.LogError(RpcProblem(problem)))

  override def logWarning(problem: Problem): Unit =
    send(RpcProblemMessage.LogWarning(RpcProblem(problem)))

  override def logInfo(problem: Problem): Unit =
    send(RpcProblemMessage.LogInfo(RpcProblem(problem)))

  override def fileVisited(file: Path): Unit =
    send(RpcProblemMessage.FileVisited(os.Path(file)))

  override def printSummary(): Unit = send(RpcProblemMessage.PrintSummary)

  override def finish(): Unit = send(RpcProblemMessage.Finish)

  override def notifyProgress(progress: Long, total: Long): Unit =
    send(RpcProblemMessage.NotifyProgress(progress, total))
}
