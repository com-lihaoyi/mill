package mill.define

import mill.api.*
import mill.define.internal.Watchable
import mill.define.{BaseModule, NamedTask, Segments, SelectMode, Task}
import scala.util.DynamicVariable
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

trait Evaluator extends AutoCloseable{
  private[mill] def allowPositionalCommandArgs: Boolean
  private[mill] def selectiveExecution: Boolean
  private[mill] def workspace: os.Path
  private[mill] def baseLogger: ColorLogger
  private[mill] def outPath: os.Path
  private[mill] def codeSignatures: Map[String, Int]
  private[mill] def rootModule: BaseModule
  private[mill] def workerCache: mutable.Map[Segments, (Int, Val)]
  private[mill] def env: Map[String, String]

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator

  def resolveSegments(
                       scriptArgs: Seq[String],
                       selectMode: SelectMode,
                       allowPositionalCommandArgs: Boolean = false,
                       resolveToModuleTasks: Boolean = false
                     ): mill.api.Result[List[Segments]]

  def resolveTasks(
                    scriptArgs: Seq[String],
                    selectMode: SelectMode,
                    allowPositionalCommandArgs: Boolean = false,
                    resolveToModuleTasks: Boolean = false
                  ): mill.api.Result[List[NamedTask[?]]]
  def plan(tasks: Seq[Task[?]]): Plan

  def execute[T](
                  targets: Seq[Task[T]],
                  reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
                  testReporter: TestReporter = DummyTestReporter,
                  logger: ColorLogger = baseLogger,
                  serialCommandExec: Boolean = false,
                  selectiveExecution: Boolean = false
                ): Evaluator.Result[T]

  def evaluate(
                scriptArgs: Seq[String],
                selectMode: SelectMode,
                selectiveExecution: Boolean = false
              ): mill.api.Result[Evaluator.Result[Any]]
}
object Evaluator {
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  private[mill] val currentEvaluator0 = new DynamicVariable[Evaluator](null)

  private[mill] def currentEvaluator = currentEvaluator0.value match {
    case null => sys.error("No evaluator available here; Evaluator is only available in exclusive commands")
    case v => v
  }

  /**
   * @param watchable the list of [[Watchable]]s that were generated during this evaluation,
   *                  useful if you want to know what to watch in case you need to re-run it.
   * @param values A sequence of values returned by evaluation.
   * @param selectedTasks The tasks that actually were selected to be run during this evaluation
   * @param executionResults Detailed information on the results of executing each task
   */
  case class Result[T](
                        watchable: Seq[Watchable],
                        values: mill.api.Result[Seq[T]],
                        selectedTasks: Seq[Task[?]],
                        executionResults: ExecutionResults
                      )

  /**
   * Holds all [[Evaluator]]s needed to evaluate the targets of the project and all it's bootstrap projects.
   */
  case class AllBootstrapEvaluators(value: Seq[Evaluator])

  private[mill] val allBootstrapEvaluators = new DynamicVariable[Evaluator.AllBootstrapEvaluators](null)

  private[mill] val defaultEnv: Map[String, String] = System.getenv().asScala.toMap
}
