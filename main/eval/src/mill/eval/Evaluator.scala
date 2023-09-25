package mill.eval

import mill.api.{CompileProblemReporter, DummyTestReporter, Result, TestReporter, Val}
import mill.api.Strict.Agg
import mill.define.{BaseModule, Segments, Task}
import mill.eval.Evaluator.{Results, formatFailing}
import mill.util.{ColorLogger, MultiBiMap}

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.DynamicVariable

/**
 * Public facing API of the Mill evaluation logic.
 */
trait Evaluator {
  def baseLogger: ColorLogger
  def rootModule: BaseModule
  def effectiveThreadCount: Int
  def outPath: os.Path
  def externalOutPath: os.Path
  def pathsResolver: EvaluatorPathsResolver
  def workerCache: collection.Map[Segments, (Int, Val)]
  def disableCallgraphInvalidation: Boolean = false
  def evaluate(
      goals: Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger
  ): Evaluator.Results

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator
  def withFailFast(newFailFast: Boolean): Evaluator

  def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Agg[Task[_]])

  /**
   * Evaluate given task(s) and return the successful result(s), or throw an exception.
   */
  def evalOrThrow(exceptionFactory: Results => Throwable =
    r =>
      new Exception(s"Failure during task evaluation: ${formatFailing(r)}")): Evaluator.EvalOrThrow

}

object Evaluator {
  trait Results {
    def rawValues: Seq[Result[Val]]
    def evaluated: Agg[Task[_]]
    def transitive: Agg[Task[_]]
    def failing: MultiBiMap[Terminal, Result.Failing[Val]]
    def results: collection.Map[Task[_], TaskResult[Val]]

    def values: Seq[Val] = rawValues.collect { case Result.Success(v) => v }
  }

  case class TaskResult[T](result: Result[T], recalc: () => Result[T]) {
    def map[V](f: T => V) = TaskResult[V](
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
          case ex: Result.Exception => ex.toString
          case Result.Failure(t, _) => t
        }
        s"${k.render} ${fss.iterator.mkString(", ")}"
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
