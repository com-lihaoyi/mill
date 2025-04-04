package mill.runner.api
import collection.mutable
trait EvaluatorApi extends AutoCloseable{
  def evaluate(
                scriptArgs: Seq[String],
                selectMode: SelectMode,
                selectiveExecution: Boolean = false
              ): Result[EvaluatorApi.Result[Any]]

  private[mill] def workerCache: mutable.Map[String, (Int, Val)]
}
object EvaluatorApi {
  trait Result[T] {
    def watchable: Seq[Watchable]
    def values: mill.runner.api.Result[Seq[T]]
  }
}
