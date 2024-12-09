package mill.main

import mill.api.Strict
import mill.define.{InputImpl, NamedTask, Task}
import mill.eval.{CodeSigUtils, Evaluator, EvaluatorCore, GroupEvaluator, Plan, Terminal}
import mill.resolve.{Resolve, SelectMode}



private[mill] object SelectiveExecution {
  case class Signatures(inputHashes: Map[String, Int],
                        taskCodeSignatures: Map[String, Int])
  implicit val jsonify: upickle.default.ReadWriter[Signatures] = upickle.default.macroRW

  object Signatures {
    def apply(evaluator: Evaluator, targets: Seq[String]) = {
      val res = plan0(evaluator, targets).getOrElse(???)
      val inputTasksToLabels: Map[Task[_], String] = res
        .collect { case labelled if labelled.task.isInstanceOf[InputImpl[_]] =>
          labelled.task -> labelled.segments.render
        }
        .toMap

      val (sortedGroups, transitive) = Plan.plan(res.map(_.task).toSeq)

      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(sortedGroups)

      lazy val constructorHashSignatures = CodeSigUtils
        .constructorHashSignatures(evaluator.methodCodeHashSignatures)

      val results = evaluator.evaluate(Strict.Agg.from(inputTasksToLabels.keys))

      val taskCodeSignatures = transitive
        .collect { case namedTask: NamedTask[_] =>
          namedTask.ctx.segments.render -> CodeSigUtils
            .codeSigForTask(
              namedTask,
              classToTransitiveClasses,
              allTransitiveClassMethods,
              evaluator.methodCodeHashSignatures,
              constructorHashSignatures
            )
            .sum
        }
        .toMap

      new Signatures(
        inputHashes = results
          .results
          .flatMap { case (task, taskResult) =>
            inputTasksToLabels.get(task).map { l =>
              l -> taskResult.result.getOrThrow.value.hashCode
            }
          }
          .toMap,
        taskCodeSignatures = taskCodeSignatures
      )
    }
  }

  def plan0(evaluator: Evaluator, targets: Seq[String]) = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      targets,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray)
    }
  }

}
