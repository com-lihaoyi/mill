package mill.api.daemon.internal

import mill.api.daemon.*
import scala.collection.mutable

trait EvaluatorApi extends AutoCloseable {
  private[mill] def scriptModuleInit: Any = null
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
  private[mill] def transitivePrefixesApi: Map[TaskApi[?], Seq[String]] = Map()
  def uncached: Seq[TaskApi[?]]

  def values: Seq[Val]
}
object ExecutionResultsApi {
  private[mill] def formatFailing(evaluated: ExecutionResultsApi): Result.Failure = {
    Result.Failure.combine(
      for ((k, fs) <- evaluated.transitiveFailingApi.toSeq)
        yield {
          val keyPrefix =
            Logger.formatPrefix(evaluated.transitivePrefixesApi.getOrElse(k, Nil)) + k + " "

          def convertFailure(f: ExecResult.Failure[_]): Result.Failure = {
            Result.Failure(keyPrefix + f.msg, f.path, f.index, next = f.next.map(convertFailure))
          }

          fs match {
            case f: ExecResult.Failure[_] => convertFailure(f)
            case ex: ExecResult.Exception =>
              var current = List(ex.throwable)

              while (current.head.getCause != null) current = current.head.getCause :: current

              val exceptionInfos = current.reverse.map { ex =>
                val elements = ex.getStackTrace.dropRight(outerStack.value.length)
                Result.Failure.ExceptionInfo(ex.getClass.getName, ex.getMessage, elements.toSeq)
              }

              Result.Failure(keyPrefix, exception = exceptionInfos)
          }
        }
    )
  }

}
