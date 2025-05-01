package mill.main

import mill.api.Strict.Agg
import mill.api.{PathRef, Result, Val}
import mill.define._
import mill.eval.Evaluator._
import mill.eval.{Evaluator, EvaluatorPaths, Plan}
import mill.main.client.OutFiles
import mill.resolve.{Resolve, SelectMode}
import mill.util.Watchable

import scala.annotation.nowarn

object RunScript {

  type TaskName = String

  @nowarn("cat=deprecation")
  def evaluateTasksNamed(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: mill.define.SelectMode
  ): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])
  ] = evaluateTasksNamed(
    evaluator,
    scriptArgs,
    selectMode match {
      case mill.define.SelectMode.Separated => SelectMode.Separated
      case mill.define.SelectMode.Multi => SelectMode.Multi
    },
    selectiveExecution = false
  )
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

  @nowarn("cat=deprecation")
  def evaluateTasksNamed(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: mill.define.SelectMode,
      selectiveExecution: Boolean
  ): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])
  ] = evaluateTasksNamed(
    evaluator,
    scriptArgs,
    selectMode match {
      case mill.define.SelectMode.Separated => SelectMode.Separated
      case mill.define.SelectMode.Multi => SelectMode.Multi
    },
    selectiveExecution
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

    val (sortedGroups, transitive) = Plan.plan(targets)
    val terminals = sortedGroups.keys().map(t => (t.task, t)).toMap
    val selectiveExecutionEnabled = selectiveExecution && !targets.exists(_.isExclusiveCommand)

    val selectedTargetsOrErr =
      if (!selectiveExecutionEnabled) (targets, Map.empty, None)
      else {
        val newComputedMetadata = SelectiveExecution.Metadata.compute(evaluator, targets.toSeq)

        val selectiveExecutionStoredData = for {
          _ <- Option.when(os.exists(evaluator.outPath / OutFiles.millSelectiveExecution))(())
          changedTasks <- SelectiveExecution.computeChangedTasks0(evaluator, targets.toSeq, newComputedMetadata)
        } yield changedTasks

        selectiveExecutionStoredData match {
          case None =>
            // Ran when previous selective execution metadata is not available, which happens the first time you run
            // selective execution.
            (targets, Map.empty, Some(newComputedMetadata.metadata))
          case Some(changedTasks) =>
            val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
            (
              targets.filter(t => t.isExclusiveCommand || selectedSet(terminals(t).render)),
              newComputedMetadata.results,
              Some(newComputedMetadata.metadata)
            )
        }
      }

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults, maybeNewMetadata) =>
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

        maybeNewMetadata.foreach { newMetadata =>
          val allInputHashes = newMetadata.inputHashes
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
