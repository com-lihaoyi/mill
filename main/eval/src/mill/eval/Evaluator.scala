package mill.eval

import mill.api.{CompileProblemReporter, DummyTestReporter, Result, TestReporter, Val}
import mill.api.Strict.Agg
import mill.define.{BaseModule, Segments, Task}
import mill.eval.Evaluator.{Results, formatFailing}
import mill.util.{ColorLogger, MultiBiMap}

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.DynamicVariable

/**
 * Public facing API of the Mill evaluation logic.
 */
trait Evaluator extends AutoCloseable {
  def baseLogger: ColorLogger
  def rootModule: BaseModule
  def effectiveThreadCount: Int
  def outPath: os.Path
  def selectiveExecution: Boolean = false
  def externalOutPath: os.Path
  def pathsResolver: EvaluatorPathsResolver
  def methodCodeHashSignatures: Map[String, Int] = Map.empty
  // TODO In 0.13.0, workerCache should have the type of mutableWorkerCache,
  // while the latter should be removed
  def workerCache: collection.Map[Segments, (Int, Val)]
  private[mill] final def mutableWorkerCache: collection.mutable.Map[Segments, (Int, Val)] =
    workerCache match {
      case mut: collection.mutable.Map[Segments, (Int, Val)] => mut
      case _ => sys.error("Evaluator#workerCache must be a mutable map")
    }
  def disableCallgraphInvalidation: Boolean = false
  
  def evaluate(
      goals: Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger,
      serialCommandExec: Boolean = false
  ): Evaluator.Results = {
    // TODO: cleanup once we break bin-compat in Mill 0.13
    // this method should be abstract, but to preserve bin-compat, we default-implement
    // by delegating to an binary pre-existing overload, by ignoring the new parameters
    evaluate(goals, reporter, testReporter, logger): @nowarn("cat=deprecation")
  }

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator
  def withFailFast(newFailFast: Boolean): Evaluator
  def allowPositionalCommandArgs: Boolean = false
  def plan(goals: Agg[Task[_]]): Plan

  /**
   * Evaluate given task(s) and return the successful result(s), or throw an exception.
   */
  def evalOrThrow(exceptionFactory: Results => Throwable =
    r =>
      new Exception(s"Failure during task evaluation: ${formatFailing(r)}")): Evaluator.EvalOrThrow

  def close() = ()
}

object Evaluator {
  trait Results {
    def rawValues: Seq[Result[Val]]
    def evaluated: Agg[Task[_]]
    def transitive: Agg[Task[_]]
    def failing: MultiBiMap[Task[_], Result.Failing[Val]]
    def results: collection.Map[Task[_], TaskResult[Val]]
    def values: Seq[Val] = rawValues.collect { case Result.Success(v) => v }
  }

  case class TaskResult[T](result: Result[T], recalc: () => Result[T]) {
    def map[V](f: T => V): TaskResult[V] = TaskResult[V](
      result.map(f),
      () => recalc().map(f)
    )
  }

  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new DynamicVariable[mill.eval.Evaluator](null)
  val allBootstrapEvaluators = new DynamicVariable[AllBootstrapEvaluators](null)

  /**
   * Holds all [[Evaluator]]s needed to evaluate the targets of the project and all it's bootstrap projects.
   */
  case class AllBootstrapEvaluators(value: Seq[Evaluator])

  val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  def formatFailing(evaluated: Evaluator.Results): String = {
    (for ((k, fs) <- evaluated.failing.items())
      yield {
        val fss = fs.map {
          case Result.Failure(t, _) => t
          case Result.Exception(Result.Failure(t, _), _) => t
          case ex: Result.Exception => ex.toString
        }
        s"${k} ${fss.iterator.mkString(", ")}"
      }).mkString("\n")
  }

  case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)

  object Cached {
    implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
  }

  trait EvalOrThrow {
    def apply[T: ClassTag](task: Task[T]): T
    def apply[T: ClassTag](tasks: Seq[Task[T]]): Seq[T]
  }
}
