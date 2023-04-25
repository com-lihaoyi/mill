package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.{EitherOps, Watched}
import mill.define.SelectMode
import mill.define.ParseArgs
import mill.api.{PathRef, Result}
import mill.api.Strict.Agg
import scala.reflect.ClassTag
import mill.define.ParseArgs.TargetsWithParams

object RunScript {

  type TaskName = String

  def evaluateTasks(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]])] = {
    for (targets <- ResolveTasks.resolveTasks(evaluator, scriptArgs, selectMode))
      yield {
        val (watched, res) = evaluate(evaluator, Agg.from(targets.distinct))

        val watched2 = for {
          x <- res.toSeq
          (Watched(_, extraWatched), _) <- x
          w <- extraWatched
        } yield w

        (watched ++ watched2, res)
      }
  }

  def evaluateTasksNamed[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])] = {
    for (targets <- ResolveTasks.resolveTasks(evaluator, scriptArgs, selectMode))
      yield {
        val (watched, res) = evaluateNamed(evaluator, Agg.from(targets.distinct))

        val watched2 = for {
          x <- res.toSeq
          (Watched(_, extraWatched), _) <- x
          w <- extraWatched
        } yield w

        (watched ++ watched2, res)
      }
  }

  def evaluate(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]]) = {
    val (watched, results) = evaluateNamed(evaluator, targets)
    // we drop the task name in the inner tuple
    (watched, results.map(_.map(p => (p._1, p._2.map(_._2)))))
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[PathRef], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {
    val evaluated: Evaluator.Results = evaluator.evaluate(targets)
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: SourcesImpl, Result.Success(ps: Seq[PathRef])) => ps
        case (t: SourceImpl, Result.Success(p: PathRef)) => Seq(p)
      }
      .flatten
      .toSeq

    val errorStr = Evaluator.formatFailing(evaluated)

    evaluated.failing.keyCount match {
      case 0 =>
        val nameAndJson = for (t <- targets.toSeq) yield {
          t match {
            case t: mill.define.NamedTask[_] =>
              val jsonFile = EvaluatorPaths.resolveDestPaths(evaluator.outPath, t).meta
              val metadata = upickle.default.read[Evaluator.Cached](ujson.read(jsonFile.toIO))
              Some(t.toString, metadata.value)

            case _ => None
          }
        }

        watched -> Right(evaluated.values.zip(nameAndJson))
      case n => watched -> Left(s"$n targets failed\n$errorStr")
    }
  }

}
