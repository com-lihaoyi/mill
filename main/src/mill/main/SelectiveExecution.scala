package mill.main

import mill.api.Strict
import mill.define.{InputImpl, NamedTask, Task}
import mill.eval.{CodeSigUtils, Evaluator, Plan, Terminal}
import mill.main.client.OutFiles
import mill.util.SpanningForest.breadthFirst
import mill.resolve.{Resolve, SelectMode}

private[mill] object SelectiveExecution {
  case class Metadata(inputHashes: Map[String, Int], methodCodeHashSignatures: Map[String, Int])
  implicit val rw: upickle.default.ReadWriter[Metadata] = upickle.default.macroRW

  object Metadata {
    def compute(evaluator: Evaluator, tasks: Seq[String]): Either[String, Metadata] = {
      for (transitive <- plan0(evaluator, tasks)) yield {
        val inputTasksToLabels: Map[Task[_], String] = transitive
          .collect { case Terminal.Labelled(task: InputImpl[_], segments) =>
            task -> segments.render
          }
          .toMap

        val results = evaluator.evaluate(Strict.Agg.from(inputTasksToLabels.keys))

        new Metadata(
          inputHashes = results
            .results
            .flatMap { case (task, taskResult) =>
              inputTasksToLabels.get(task).map { l =>
                l -> taskResult.result.getOrThrow.value.hashCode
              }
            }
            .toMap,
          methodCodeHashSignatures = evaluator.methodCodeHashSignatures
        )
      }
    }
  }

  def plan0(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Either[String, Array[Terminal.Labelled[_]]] = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      tasks,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray)
    }
  }

  def computeHashCodeSignatures(
      res: Array[Terminal.Labelled[_]],
      methodCodeHashSignatures: Map[String, Int]
  ): Map[String, Int] = {

    val (sortedGroups, transitive) = Plan.plan(res.map(_.task).toSeq)

    val (classToTransitiveClasses, allTransitiveClassMethods) =
      CodeSigUtils.precomputeMethodNamesPerClass(sortedGroups)

    lazy val constructorHashSignatures = CodeSigUtils
      .constructorHashSignatures(methodCodeHashSignatures)

    sortedGroups.keys()
      .collect { case Terminal.Labelled(namedTask: NamedTask[_], segments) =>
        segments.render -> CodeSigUtils
          .codeSigForTask(
            namedTask,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            methodCodeHashSignatures,
            constructorHashSignatures
          )
          .sum
      }
      .toMap
  }

  def computeDownstream(
      evaluator: Evaluator,
      tasks: Seq[String],
      oldHashes: Metadata,
      newHashes: Metadata
  ): Seq[Task[Any]] = {
    val terminals = SelectiveExecution.plan0(evaluator, tasks).getOrElse(???)
    val namesToTasks = terminals.map(t => (t.render -> t.task)).toMap

    def diffMap[K, V](lhs: Map[K, V], rhs: Map[K, V]) = {
      (lhs.keys ++ rhs.keys)
        .iterator
        .distinct
        .filter { k => lhs.get(k) != rhs.get(k) }
        .toSet
    }

    val changedInputNames = diffMap(oldHashes.inputHashes, newHashes.inputHashes)
    val changedCodeNames = diffMap(
      computeHashCodeSignatures(terminals, oldHashes.methodCodeHashSignatures),
      computeHashCodeSignatures(terminals, newHashes.methodCodeHashSignatures)
    )

    val changedRootTasks = (changedInputNames ++ changedCodeNames)
      .flatMap(namesToTasks.get(_): Option[Task[_]])

    val allNodes = breadthFirst(terminals.map(_.task: Task[_]))(_.inputs)
    val downstreamEdgeMap = allNodes
      .flatMap(t => t.inputs.map(_ -> t))
      .groupMap(_._1)(_._2)

    breadthFirst(changedRootTasks)(downstreamEdgeMap.getOrElse(_, Nil))
  }

  def saveMetadata(evaluator: Evaluator, metadata: SelectiveExecution.Metadata): Unit = {
    os.write.over(
      evaluator.outPath / OutFiles.millSelectiveExecution,
      upickle.default.write(metadata, indent = 2)
    )
  }

  def diffMetadata(evaluator: Evaluator, tasks: Seq[String]): Either[String, Seq[String]] = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)
    if (oldMetadataTxt == "") {
      Resolve.Segments.resolve(
        evaluator.rootModule,
        tasks,
        SelectMode.Separated,
        evaluator.allowPositionalCommandArgs
      ).map(_.map(_.render))
    } else {
      val oldMetadata = upickle.default.read[SelectiveExecution.Metadata](oldMetadataTxt)
      for (newMetadata <- SelectiveExecution.Metadata.compute(evaluator, tasks)) yield {
        SelectiveExecution.computeDownstream(evaluator, tasks, oldMetadata, newMetadata)
          .collect { case n: NamedTask[_] => n.ctx.segments.render }

      }
    }
  }

  def resolve0(evaluator: Evaluator, tasks: Seq[String]): Either[String, Array[String]] = {
    for {
      resolved <- Resolve.Tasks.resolve(evaluator.rootModule, tasks, SelectMode.Separated)
      diffed <- SelectiveExecution.diffMetadata(evaluator, tasks)
    } yield {
      resolved
        .map(_.ctx.segments.render)
        .toSet
        .intersect(diffed.toSet)
        .toArray
        .sorted
    }
  }
}
