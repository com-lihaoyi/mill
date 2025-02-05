package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths, Plan}
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
    for (targets <- resolved)
      yield evaluateNamed(evaluator, Agg.from(targets), selectiveExecution)
  }

  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[NamedTask[Any]]
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) =
    evaluateNamed(evaluator, targets, selectiveExecution = false)

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[NamedTask[Any]],
      selectiveExecution: Boolean = false
  ): (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {

    val plan = Plan.plan(targets)
    val terminals = plan.sortedGroups.keys().map(t => (t.task, t)).toMap
    val selectiveExecutionEnabled = selectiveExecution && !targets.exists(_.isExclusiveCommand)

    val selectedTargetsOrErr =
      if (
        selectiveExecutionEnabled && os.exists(evaluator.outPath / OutFiles.millSelectiveExecution)
      ) {
        val changedTasks = SelectiveExecution.computeChangedTasks0(evaluator, targets.toSeq)
        val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
        (
          targets.filter(t => t.isExclusiveCommand || selectedSet(terminals(t).render)),
          changedTasks.results
        )
      } else (targets -> Map.empty)

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults) =>
        val evaluated: Results = evaluator.evaluate(selectedTargets, serialCommandExec = true)
        val watched = (evaluated.results.iterator ++ selectiveResults)
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
              (terminals(t).render, value.##)
          }
          .toMap

        if (selectiveExecutionEnabled) {
          SelectiveExecution.saveMetadata(
            evaluator,
            SelectiveExecution.Metadata(allInputHashes, evaluator.methodCodeHashSignatures)
          )
        }

        val errorStr = Evaluator.formatFailing(evaluated)
        evaluated.failing.keyCount match {
          case 0 =>
            val nameAndJson = for (t <- selectedTargets.toSeq) yield {
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
}
