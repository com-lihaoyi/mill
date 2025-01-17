package mill.main

import mill.api.{Strict, Val}
import mill.define.{InputImpl, NamedTask, Task}
import mill.eval.{CodeSigUtils, Evaluator, EvaluatorCore, EvaluatorLogs, Plan, Terminal}
import mill.main.client.OutFiles
import mill.util.SpanningForest.breadthFirst
import mill.resolve.{Resolve, SelectMode}
import mill.util.SpanningForest


private[mill] object SelectiveExecution {
  case class Metadata(inputHashes: Map[String, Int], methodCodeHashSignatures: Map[String, Int])

  implicit val rw: upickle.default.ReadWriter[Metadata] = upickle.default.macroRW

  object Metadata {
    def compute(
        evaluator: Evaluator,
        tasks: Seq[String]
    ): Either[String, (Metadata, Map[Task[_], Evaluator.TaskResult[Val]])] = {
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
        ) -> results.results.toMap
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
  ): (Set[Task[_]], Seq[Task[Any]]) = {
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
    val downstreamEdgeMap = SpanningForest.reverseEdges(allNodes.map(t => (t, t.inputs)))

    (changedRootTasks, breadthFirst(changedRootTasks)(downstreamEdgeMap.getOrElse(_, Nil)))
  }

  def saveMetadata(evaluator: Evaluator, metadata: SelectiveExecution.Metadata): Unit = {
    os.write.over(
      evaluator.outPath / OutFiles.millSelectiveExecution,
      upickle.default.write(metadata, indent = 2)
    )
  }

  case class ChangedTasks(changedRootTasks: Set[Task[_]], downstreamTasks: Seq[NamedTask[_]], results: Map[Task[_], Evaluator.TaskResult[Val]])
  def computeChangedTasks0(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Either[String, ChangedTasks] = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)
    if (oldMetadataTxt == "") {
      Resolve.Tasks.resolve(
        evaluator.rootModule,
        tasks,
        SelectMode.Separated,
        evaluator.allowPositionalCommandArgs
      ).map(t => ChangedTasks(t.toSet, t,  Map.empty))
    } else {
      val oldMetadata = upickle.default.read[SelectiveExecution.Metadata](oldMetadataTxt)
      for (x <- SelectiveExecution.Metadata.compute(evaluator, tasks)) yield {
        val (newMetadata, results) = x

        val (changedRootTasks, downstreamTasks) = SelectiveExecution
          .computeDownstream(evaluator, tasks, oldMetadata, newMetadata)
        ChangedTasks(changedRootTasks, downstreamTasks.collect { case n: NamedTask[_] => n }, results)

      }
    }
  }

  def resolve0(evaluator: Evaluator, tasks: Seq[String]): Either[String, Array[String]] = {
    for {
      resolved <- Resolve.Tasks.resolve(evaluator.rootModule, tasks, SelectMode.Separated)
      changedTasks <- SelectiveExecution.computeChangedTasks0(evaluator, tasks)
    } yield {
      resolved
        .map(_.ctx.segments.render)
        .toSet
        .intersect(changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet)
        .toArray
        .sorted
    }
  }

  def resolveTree(evaluator: Evaluator, tasks: Seq[String]): Either[String, ujson.Value] = {
    for (diffTasks <- SelectiveExecution.computeChangedTasks0(evaluator, tasks))yield {
      val taskSet = diffTasks.downstreamTasks.toSet[Task[_]]
      val (sortedGroups, transitive) = Plan.plan(mill.api.Loose.Agg.from(diffTasks.downstreamTasks))
      val indexToTerminal = sortedGroups.keys().toArray.filter(t => taskSet.contains(t.task))

      val terminalToIndex = indexToTerminal.zipWithIndex.toMap

      val interGroupDeps = EvaluatorCore.findInterGroupDeps(sortedGroups)

      val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)

      val indexEdges: Array[Array[Int]] = indexToTerminal.map(t =>
        reverseInterGroupDeps.getOrElse(t, Nil).flatMap(terminalToIndex.get(_)).toArray
      )
      SpanningForest.writeJson(
        indexEdges = indexEdges,
        interestingIndices = indexToTerminal.indices.toSet,
        render = indexToTerminal(_).render
      )
    }
  }
}
