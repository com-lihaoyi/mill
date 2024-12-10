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

  def evaluateTasksNamed(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])
  ] = evaluateTasksNamed(
    evaluator,
    scriptArgs,
    selectMode,
    selectiveExecution = false
  )
  def evaluateTasksNamed(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])
  ] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
      Resolve.Tasks.resolve(
        evaluator.rootModule,
        scriptArgs,
        selectMode,
        evaluator.allowPositionalCommandArgs
      )
    }
    for (targets <- resolved) yield evaluateNamed(evaluator, Agg.from(targets), selectiveExecution)
  }

  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) =
    evaluateNamed(evaluator, targets, selectiveExecution = false)

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[Task[Any]],
      selectiveExecution: Boolean = false
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {
    val selectedTargets =
      if (!selectiveExecution || !os.exists(evaluator.outPath / OutFiles.millSelectiveExecution))
        targets
      else {
        val oldHashes = upickle.default.read[SelectiveExecution.Signatures](
          os.read(evaluator.outPath / OutFiles.millSelectiveExecution)
        )
        val targetLabels = targets.map { case t: NamedTask[_] => t.ctx.segments.render }.toSeq
        val newHashes = SelectiveExecution.Signatures(evaluator, targetLabels)
        val res = SelectiveExecution
          .computeDownstream(evaluator, targetLabels, oldHashes, newHashes)
          .collect { case n: NamedTask[_] => n.ctx.segments.render }
          .toSet
        val res2 =
          targets.collect { case n: NamedTask[_] if res.contains(n.ctx.segments.render) => n }

        Agg.from(res2)
      }
    val evaluated: Results = evaluator.evaluate(selectedTargets, serialCommandExec = true)

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

    val allInputHashes = evaluated.results
      .iterator
      .collect {
        case (t: InputImpl[_], TaskResult(Result.Success(Val(value)), _)) =>
          (t.ctx.segments.render, value.##)
      }
      .toMap

    if (selectiveExecution) {
      os.write.over(
        evaluator.outPath / OutFiles.millSelectiveExecution,
        upickle.default.write(
          SelectiveExecution.Signatures(allInputHashes, evaluator.methodCodeHashSignatures)
        )
      )
    }
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

        watched -> Right(evaluated.values.zip(nameAndJson))
      case n => watched -> Left(s"$n tasks failed\n$errorStr")
    }
  }

}
