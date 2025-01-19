package mill.main

import mill.api.{Strict, Val}
import mill.define.{InputImpl, NamedTask, Task}
import mill.eval.{CodeSigUtils, Evaluator, EvaluatorCore, Plan, Terminal}
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
        tasks: Seq[NamedTask[_]]
    ): (Metadata, Map[Task[_], Evaluator.TaskResult[Val]]) = {
      val (sortedGroups, transitive) = Plan.plan(tasks)
      val inputTasksToLabels: Map[Task[_], String] = sortedGroups.keys()
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
      tasks: Seq[NamedTask[_]],
      oldHashes: Metadata,
      newHashes: Metadata
  ): (Set[Task[_]], Seq[Task[Any]]) = {
    val (sortedGroups, transitive) = Plan.plan(tasks)
    val terminals = sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray
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

    (
      changedRootTasks,
      breadthFirst(changedRootTasks) { t =>
        downstreamEdgeMap.getOrElse(t.asInstanceOf[Task[Nothing]], Nil)
      }
    )
  }

  def saveMetadata(evaluator: Evaluator, metadata: SelectiveExecution.Metadata): Unit = {
    os.write.over(
      evaluator.outPath / OutFiles.millSelectiveExecution,
      upickle.default.write(metadata, indent = 2)
    )
  }

  case class ChangedTasks(
      resolved: Seq[NamedTask[_]],
      changedRootTasks: Set[NamedTask[_]],
      downstreamTasks: Seq[NamedTask[_]],
      results: Map[Task[_], Evaluator.TaskResult[Val]]
  )

  def computeChangedTasks(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Either[String, ChangedTasks] = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map(computeChangedTasks0(evaluator, _))
  }

  def computeChangedTasks0(evaluator: Evaluator, tasks: Seq[NamedTask[_]]): ChangedTasks = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)
    if (oldMetadataTxt == "") ChangedTasks(tasks, tasks.toSet, tasks, Map.empty)
    else {
      val oldMetadata = upickle.default.read[SelectiveExecution.Metadata](oldMetadataTxt)
      val (newMetadata, results) = SelectiveExecution.Metadata.compute(evaluator, tasks)

      val (changedRootTasks, downstreamTasks) =
        SelectiveExecution.computeDownstream(tasks, oldMetadata, newMetadata)

      ChangedTasks(
        tasks,
        changedRootTasks.collect { case n: NamedTask[_] => n },
        downstreamTasks.collect { case n: NamedTask[_] => n },
        results
      )
    }
  }

  def resolve0(evaluator: Evaluator, tasks: Seq[String]): Either[String, Array[String]] = {
    for {
      resolved <- Resolve.Tasks.resolve(evaluator.rootModule, tasks, SelectMode.Separated)
      changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)
    } yield {
      val resolvedSet = resolved.map(_.ctx.segments.render).toSet
      val downstreamSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
      resolvedSet.intersect(downstreamSet).toArray.sorted
    }
  }

  def resolveChanged(evaluator: Evaluator, tasks: Seq[String]): Either[String, Seq[String]] = {
    for (changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)) yield {
      changedTasks.changedRootTasks.map(_.ctx.segments.render).toSeq.sorted
    }
  }

  def resolveTree(evaluator: Evaluator, tasks: Seq[String]): Either[String, ujson.Value] = {
    for (changedTasks <- SelectiveExecution.computeChangedTasks(evaluator, tasks)) yield {
      val taskSet = changedTasks.downstreamTasks.toSet[Task[_]]
      val (sortedGroups, transitive) =
        Plan.plan(mill.api.Loose.Agg.from(changedTasks.downstreamTasks))
      val indexToTerminal = sortedGroups.keys().toArray.filter(t => taskSet.contains(t.task))

      val interGroupDeps = EvaluatorCore.findInterGroupDeps(sortedGroups)

      val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)

      val (vertexToIndex, edgeIndices) =
        SpanningForest.graphMapToIndices(indexToTerminal, reverseInterGroupDeps)

      val json = SpanningForest.writeJson(
        indexEdges = edgeIndices,
        interestingIndices = indexToTerminal.indices.toSet,
        render = indexToTerminal(_).render
      )

      // Simplify the tree structure to only show the direct paths to the tasks
      // resolved directly, removing the other branches, since those tasks are
      // the ones that the user probably cares about
      val resolvedTaskLabels = changedTasks.resolved.map(_.ctx.segments.render).toSet
      def simplifyJson(j: ujson.Obj): Option[ujson.Obj] = {
        val map = j.value.flatMap { case (k, v: ujson.Obj) =>
          simplifyJson(v)
            .map((k, _))
            .orElse(Option.when(resolvedTaskLabels.contains(k)) { k -> v })
        }
        Option.when(map.nonEmpty)(ujson.Obj.from(map))
      }

      simplifyJson(json).getOrElse(ujson.Obj())
    }
  }
}
