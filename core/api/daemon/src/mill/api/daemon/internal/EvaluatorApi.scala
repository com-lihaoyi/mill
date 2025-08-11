package mill.api.daemon.internal

import mill.api.daemon.*
import scala.collection.mutable

trait EvaluatorApi extends AutoCloseable {
  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      reporter: Int => Option[CompileProblemReporter] = _ => None,
      selectiveExecution: Boolean = false
  ): Result[EvaluatorApi.Result[Any]]

  private[mill] def executeApi[T](
      tasks: Seq[TaskApi[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      logger: Logger = null,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): EvaluatorApi.Result[T]

  private[mill] def workerCache: mutable.Map[String, (Int, Val)]

  private[mill] def executeApi[T](tasks: Seq[TaskApi[T]]): EvaluatorApi.Result[T]
  private[mill] def baseLogger: Logger
  private[mill] def rootModule: BaseModuleApi
  private[mill] def outPathJava: java.nio.file.Path
}
object EvaluatorApi {
  trait Result[T] {
    def watchable: Seq[Watchable]
    def values: mill.api.daemon.Result[Seq[T]]

    def selectedTasks: Seq[TaskApi[?]]
    def executionResults: ExecutionResultsApi
  }
}

trait ExecutionResultsApi {
  def results: Seq[ExecResult[Val]]
  private[mill] def transitiveResultsApi: Map[TaskApi[?], ExecResult[Val]]

  private[mill] def transitiveFailingApi: Map[TaskApi[?], ExecResult.Failing[Val]]
  def uncached: Seq[TaskApi[?]]

  def values: Seq[Val]
}
object ExecutionResultsApi {
  private[mill] def formatFailing(evaluated: ExecutionResultsApi): String = {
    (for ((k, fs) <- evaluated.transitiveFailingApi)
      yield {
        val fss = fs match {
          case ExecResult.Failure(t) => t
          case ex: ExecResult.Exception => ex.toString
        }
        s"$k $fss"
      }).mkString("\n")
  }

}
