package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.Watchable
import mill.api.{PathRef, Result, Val}
import mill.api.Strict.Agg
import Evaluator._
import mill.main.client.OutFiles
import mill.resolve.{Resolve, SelectMode}

object RunScript {

  type TaskName = String
  def evaluateTasksNamed(evaluator: Evaluator,
                         scriptArgs: Seq[String],
                         selectMode: SelectMode): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])
  ] = {
    evaluateTasksNamed0(evaluator, scriptArgs, selectMode)
      .map{case (ws, either) => (ws, either.map{case (r, v) => v})}
  }

  def evaluateTasksNamed0(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
  ): Either[
    String,
    (Seq[Watchable], Either[String, (Map[Task[_], TaskResult[Val]], Seq[(Any, Option[(TaskName, ujson.Value)])])])
  ] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
      Resolve.Tasks.resolve(
        evaluator.rootModule,
        scriptArgs,
        selectMode,
        evaluator.allowPositionalCommandArgs
      )
    }
    for (targets <- resolved)
      yield evaluateNamed0(evaluator, Agg.from(targets))
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[NamedTask[Any]]
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {
    val (ws, either) = evaluateNamed0(evaluator, targets)
    (ws, either.map{case (r, v) => v})
  }

  def evaluateNamed0(
      evaluator: Evaluator,
      targets: Agg[NamedTask[Any]]
  ): (Seq[Watchable], Either[String, (Map[Task[_], TaskResult[Val]], Seq[(Any, Option[(TaskName, ujson.Value)])])]) = {
    val evaluated: Results = evaluator.evaluate(targets, serialCommandExec = true)
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: SourcesImpl, TaskResult(Result.Success(Val(ps: Seq[PathRef])), _)) =>
          ps.map(Watchable.Path(_))
        case (t: SourceImpl, TaskResult(Result.Success(Val(p: PathRef)), _)) =>
          Seq(Watchable.Path(p))
        case (t: InputImpl[_], TaskResult(result, recalc)) =>
          val pretty = t.ctx0.fileName + ":" + t.ctx0.lineNum
          Seq(Watchable.Value(() => recalc().hashCode(), result.hashCode(), pretty))
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
              Some((t.toString, metadata.value))

            case _ => None
          }
        }

        val rhs: Seq[(Any, Option[(TaskName, ujson.Value)])] = evaluated.values.zip(nameAndJson)
        val results: Map[Task[_], TaskResult[Val]] = evaluated.results.toMap

        watched -> Right(results -> rhs)

      case n => watched -> Left(s"$n tasks failed\n$errorStr")
    }
  }
}
