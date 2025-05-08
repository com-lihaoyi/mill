package mill.define

import mill.api.internal.{CompileProblemReporter, TestReporter}
import mill.api.*
import mill.define.internal.Watchable
import mill.api.internal.{EvaluatorApi, TaskApi}
import scala.util.DynamicVariable
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

trait Evaluator extends AutoCloseable with EvaluatorApi {
  private[mill] def allowPositionalCommandArgs: Boolean
  private[mill] def selectiveExecution: Boolean
  private[mill] def workspace: os.Path
  private[mill] def baseLogger: Logger
  private[mill] def outPath: os.Path
  private[mill] def outPathJava = outPath.toNIO
  private[mill] def codeSignatures: Map[String, Int]
  private[mill] def rootModule: BaseModule
  private[mill] def workerCache: mutable.Map[String, (Int, Val)]
  private[mill] def env: Map[String, String]
  private[mill] def effectiveThreadCount: Int
  private[mill] def offline: Boolean

  def withBaseLogger(newBaseLogger: Logger): Evaluator

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
  def resolveModulesOrTasks(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): mill.api.Result[List[Either[Module, NamedTask[?]]]]

  def plan(tasks: Seq[Task[?]]): Plan

  def groupAroundImportantTargets[T](topoSortedTargets: mill.define.internal.TopoSorted)(
      important: PartialFunction[
        Task[?],
        T
      ]
  ): MultiBiMap[T, Task[?]]

  /**
   * Collects all transitive dependencies (targets) of the given targets,
   * including the given targets.
   */
  def transitiveTargets(sourceTargets: Seq[Task[?]]): IndexedSeq[Task[?]]

  /**
   * Takes the given targets, finds all the targets they transitively depend
   * on, and sort them topologically. Fails if there are dependency cycles
   */
  def topoSorted(transitiveTargets: IndexedSeq[Task[?]]): mill.define.internal.TopoSorted

  private[mill] def executeApi[T](targets: Seq[TaskApi[T]]): Evaluator.Result[T] =
    execute[T](targets.map(_.asInstanceOf[Task[T]]))

  def execute[T](
      targets: Seq[Task[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      logger: Logger = baseLogger,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): Evaluator.Result[T]

  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): mill.api.Result[Evaluator.Result[Any]]

  private[mill] def executeApi[T](
      targets: Seq[TaskApi[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      logger: Logger = null,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): EvaluatorApi.Result[T] = {
    os.checker.withValue(os.Checker.Nop) {
      execute(
        targets.map(_.asInstanceOf[Task[T]]),
        reporter,
        testReporter,
        logger,
        serialCommandExec,
        selectiveExecution
      )
    }
  }

  /**
   * APIs related to selective execution
   */
  def selective: SelectiveExecution
}
object Evaluator {
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  private val currentEvaluator0 = new DynamicVariable[Evaluator](null)
  private[mill] def withCurrentEvaluator[T](ev: Evaluator)(t: => T) = {
    // Make sure we only put a `EvaluatorProxy` in the `DynamicVariable` rather than a
    // raw `Evaluator`, and `.close()` it after we're done to `null` out the contents.
    // This is so that if the `InheritableThreadLocal` gets captured by long-lived spawned
    // thread pools, we do not end up leaking the contents.
    scala.util.Using.resource(new EvaluatorProxy(() => ev)){ev2 =>
      currentEvaluator0.withValue(ev2){
        t
      }
    }
  }

  private[mill] def currentEvaluator = currentEvaluator0.value match {
    case null =>
      sys.error("No evaluator available here; Evaluator is only available in exclusive commands")
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
  ) extends EvaluatorApi.Result[T]

  private[mill] val defaultEnv: Map[String, String] = System.getenv().asScala.toMap
}
