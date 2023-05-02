package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.Watchable
import mill.api.{PathRef, Result}
import mill.api.Strict.Agg


object RunScript {

  type TaskName = String

  def evaluateTasksNamed[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])] = {
    for (targets <- ResolveTasks.resolve(evaluator, scriptArgs, selectMode))
    yield evaluateNamed(evaluator, Agg.from(targets.distinct))
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {
    val evaluated: Evaluator.Results = evaluator.evaluate(targets)

    val watched = evaluated.results
      .iterator
      .collect {
        case (t: SourcesImpl, Evaluator.EvalResult(Result.Success(ps: Seq[PathRef]), None)) => ps.map(Watchable.Path(_))
        case (t: SourceImpl, Evaluator.EvalResult(Result.Success(p: PathRef), None)) => Seq(Watchable.Path(p))
        case (t: InputImpl[_], Evaluator.EvalResult(Result.Success(v), Some(recalc))) =>
          val pretty = t.ctx0.fileName + ":" + t.ctx0.lineNum
          Seq(Watchable.Value(() => recalc().hashCode(), recalc().hashCode(), pretty))
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
