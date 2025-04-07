package mill.runner.api
import collection.mutable
import scala.util.DynamicVariable
trait EvaluatorApi extends AutoCloseable {
  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): Result[EvaluatorApi.Result[Any]]

  private[mill] def workerCache: mutable.Map[String, (Int, Val)]

  def execute[T](targets: Seq[TaskApi[T]]): EvaluatorApi.Result[T]
  private[mill] def baseLogger: Logger
  private[mill] def rootModule: BaseModuleApi
}
object EvaluatorApi {
  trait Result[T] {
    def watchable: Seq[Watchable]
    def values: mill.runner.api.Result[Seq[T]]
  }

  /**
   * Holds all [[Evaluator]]s needed to evaluate the targets of the project and all it's bootstrap projects.
   */
  case class AllBootstrapEvaluators(value: Seq[EvaluatorApi])

  private[mill] val allBootstrapEvaluators =
    new DynamicVariable[AllBootstrapEvaluators](null)

}

