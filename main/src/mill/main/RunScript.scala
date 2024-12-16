package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths, Terminal}
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
  ] = mill.eval.Evaluator.currentEvaluator.withValue(evaluator) {
    val enableSelective = selectiveExecution && os.exists(evaluator.outPath / OutFiles.millSelectiveExecution)
    val selectedTargetsOrErr =
      if (enableSelective) {
        SelectiveExecution.resolve0(evaluator, scriptArgs).map(_.flatMap(Array("+", _)).drop(1))
      } else {
        Right(scriptArgs.toArray)
      }

    selectedTargetsOrErr.flatMap { resolvedTaskNames =>
      if (enableSelective && resolvedTaskNames.isEmpty) Right((Nil, Right(Nil)))
      else {
        val resolved = Resolve.Tasks.resolve(
          evaluator.rootModule,
          resolvedTaskNames,
          selectMode,
          evaluator.allowPositionalCommandArgs
        )


        resolved.map { t =>
          val evaluated = evaluateNamed0(evaluator, Agg.from(t))
          if (selectiveExecution) {
            for (res <- evaluated._2) {
              val (results, terminals, _) = res
              val allInputHashes = results
                .iterator
                .collect {
                  case (t: InputImpl[_], TaskResult(Result.Success(Val(value)), _)) =>
                    (terminals(t).render, value.##)
                }
                .toMap
              SelectiveExecution.saveMetadata(
                evaluator,
                SelectiveExecution.Metadata(allInputHashes, evaluator.methodCodeHashSignatures)
              )
            }
          }
          val (ws, either) = evaluated
          (ws, either.map { case (r, t, v) => v })
        }
      }
    }
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
    (ws, either.map { case (r, t, v) => v })
  }

  def evaluateNamed0(
      evaluator: Evaluator,
      targets: Agg[NamedTask[Any]]
  ): (
      Seq[Watchable],
      Either[
        String,
        (
            Map[Task[_], TaskResult[Val]],
            Map[Task[_], Terminal],
            Seq[(Any, Option[(TaskName, ujson.Value)])]
        )
      ]
  ) = {
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

        watched -> Right((results, evaluated.terminals, rhs))

      case n => watched -> Left(s"$n tasks failed\n$errorStr")
    }
  }
}
