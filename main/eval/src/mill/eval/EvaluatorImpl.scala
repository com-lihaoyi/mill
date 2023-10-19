package mill.eval

import mill.api.Val
import mill.api.Strict.Agg
import mill.define._
import mill.util._
import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * Implementation of [[Evaluator]], which serves both as internal logic as well
 * as an odd bag of user-facing helper methods. Internal-only logic is
 * extracted into [[EvaluatorCore]]
 */
private[mill] case class EvaluatorImpl(
    home: os.Path,
    workspace: os.Path,
    outPath: os.Path,
    externalOutPath: os.Path,
    rootModule: mill.define.BaseModule,
    baseLogger: ColorLogger,
    classLoaderSigHash: Int,
    classLoaderIdentityHash: Int,
    workerCache: mutable.Map[Segments, (Int, Val)] = mutable.Map.empty,
    env: Map[String, String] = Evaluator.defaultEnv,
    failFast: Boolean = true,
    threadCount: Option[Int] = Some(1),
    scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])] = Map.empty,
    methodCodeHashSignatures: Map[String, Int],
    override val disableCallgraphInvalidation: Boolean
) extends Evaluator with EvaluatorCore {
  import EvaluatorImpl._

  val pathsResolver: EvaluatorPathsResolver = EvaluatorPathsResolver.default(outPath)

  override def withBaseLogger(newBaseLogger: ColorLogger): Evaluator =
    this.copy(baseLogger = newBaseLogger)

  override def withFailFast(newFailFast: Boolean): Evaluator =
    this.copy(failFast = newFailFast)

  override def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Agg[Task[_]]) = {
    Plan.plan(goals)
  }

  override def evalOrThrow(exceptionFactory: Evaluator.Results => Throwable)
      : Evaluator.EvalOrThrow =
    new EvalOrThrow(this, exceptionFactory)
}

private[mill] object EvaluatorImpl {
  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: Evaluator.Results => Throwable)
      extends Evaluator.EvalOrThrow {
    def apply[T: ClassTag](task: Task[T]): T =
      evaluator.evaluate(Agg(task)) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r =>
          // Input is a single-item Agg, so we also expect a single-item result
          val Seq(Val(e: T)) = r.values
          e
      }

    def apply[T: ClassTag](tasks: Seq[Task[T]]): Seq[T] =
      evaluator.evaluate(tasks) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r => r.values.map(_.value).asInstanceOf[Seq[T]]
      }
  }
}
