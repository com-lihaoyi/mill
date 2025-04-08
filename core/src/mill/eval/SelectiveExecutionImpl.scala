package mill.eval

import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles
import mill.define.{Evaluator, InputImpl, NamedTask, SelectMode, Task, SelectiveExecution}
import mill.define.SelectiveExecution.ChangedTasks
import mill.exec.{CodeSigUtils, Execution, PlanImpl}
import mill.internal.SpanningForest
import mill.internal.SpanningForest.breadthFirst

private[mill] class SelectiveExecutionImpl(evaluator: Evaluator)
    extends mill.define.SelectiveExecution {

  def computeHashCodeSignatures(
      transitiveNamed: Seq[NamedTask[?]],
      codeSignatures: Map[String, Int]
  ): Map[String, Int] = {

    val (classToTransitiveClasses, allTransitiveClassMethods) =
      CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

    lazy val constructorHashSignatures = CodeSigUtils
      .constructorHashSignatures(codeSignatures)

    transitiveNamed
      .map { namedTask =>
        namedTask.ctx.segments.render -> CodeSigUtils
          .codeSigForTask(
            namedTask,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            codeSignatures,
            constructorHashSignatures
          )
          .sum
      }
      .toMap
  }

  def computeDownstream(
      transitiveNamed: Seq[NamedTask[?]],
      oldHashes: SelectiveExecution.Metadata,
      newHashes: SelectiveExecution.Metadata
  ): (Set[Task[?]], Seq[Task[Any]]) = {
    val namesToTasks = transitiveNamed.map(t => (t.ctx.segments.render -> t)).toMap

    def diffMap[K, V](lhs: Map[K, V], rhs: Map[K, V]) = {
      (lhs.keys ++ rhs.keys)
        .iterator
        .distinct
        .filter { k => lhs.get(k) != rhs.get(k) }
        .toSet
    }

    val changedInputNames = diffMap(oldHashes.inputHashes, newHashes.inputHashes)
    val changedCodeNames = diffMap(
      computeHashCodeSignatures(transitiveNamed, oldHashes.codeSignatures),
      computeHashCodeSignatures(transitiveNamed, newHashes.codeSignatures)
    )

    val changedRootTasks = (changedInputNames ++ changedCodeNames)
      .flatMap(namesToTasks.get(_): Option[Task[?]])

    val allNodes = breadthFirst(transitiveNamed.map(t => t: Task[?]))(_.inputs)
    val downstreamEdgeMap = SpanningForest.reverseEdges(allNodes.map(t => (t, t.inputs)))

    (
      changedRootTasks,
      breadthFirst(changedRootTasks) { t =>
        downstreamEdgeMap.getOrElse(t.asInstanceOf[Task[Nothing]], Nil)
      }
    )
  }

  def saveMetadata(metadata: SelectiveExecution.Metadata): Unit = {
    os.write.over(
      evaluator.outPath / OutFiles.millSelectiveExecution,
      upickle.default.write(metadata, indent = 2)
    )
  }

  def computeChangedTasks(
      tasks: Seq[String]
  ): Result[ChangedTasks] = {
    evaluator.resolveTasks(
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map(computeChangedTasks0(_))
  }

  def computeChangedTasks0(tasks: Seq[NamedTask[?]]): ChangedTasks = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)

    if (oldMetadataTxt == "") ChangedTasks(tasks, tasks.toSet, tasks, Map.empty)
    else {
      val transitiveNamed = PlanImpl.transitiveNamed(tasks)
      val oldMetadata = upickle.default.read[SelectiveExecution.Metadata](oldMetadataTxt)
      val (newMetadata, results) =
        SelectiveExecutionImpl.Metadata.compute0(evaluator, transitiveNamed)
      val (changedRootTasks, downstreamTasks) =
        evaluator.selective.computeDownstream(transitiveNamed, oldMetadata, newMetadata)

      ChangedTasks(
        tasks,
        changedRootTasks.collect { case n: NamedTask[_] => n },
        downstreamTasks.collect { case n: NamedTask[_] => n },
        results
      )
    }
  }

  def resolve0(tasks: Seq[String]): Result[Array[String]] = {
    for {
      resolved <- evaluator.resolveTasks(tasks, SelectMode.Separated)
      changedTasks <- this.computeChangedTasks(tasks)
    } yield {
      val resolvedSet = resolved.map(_.ctx.segments.render).toSet
      val downstreamSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
      resolvedSet.intersect(downstreamSet).toArray.sorted
    }
  }

  def resolveChanged(tasks: Seq[String]): Result[Seq[String]] = {
    for (changedTasks <- this.computeChangedTasks(tasks)) yield {
      changedTasks.changedRootTasks.map(_.ctx.segments.render).toSeq.sorted
    }
  }

  def resolveTree(tasks: Seq[String]): Result[ujson.Value] = {
    for (changedTasks <- this.computeChangedTasks(tasks)) yield {
      val taskSet = changedTasks.downstreamTasks.toSet[Task[?]]
      val plan = PlanImpl.plan(Seq.from(changedTasks.downstreamTasks))
      val indexToTerminal = plan
        .sortedGroups
        .keys()
        .toArray
        .filter(t => taskSet.contains(t))
        .sortBy(_.toString) // Sort to ensure determinism

      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)
      val reverseInterGroupDeps = SpanningForest.reverseEdges(
        interGroupDeps.toSeq.sortBy(_._1.toString) // sort to ensure determinism
      )

      val (vertexToIndex, edgeIndices) =
        SpanningForest.graphMapToIndices(indexToTerminal, reverseInterGroupDeps)

      val json = SpanningForest.writeJson(
        indexEdges = edgeIndices,
        interestingIndices = indexToTerminal.indices.toSet,
        render = indexToTerminal(_).toString
      )

      // Simplify the tree structure to only show the direct paths to the tasks
      // resolved directly, removing the other branches, since those tasks are
      // the ones that the user probably cares about
      val resolvedTaskLabels = changedTasks.resolved.map(_.ctx.segments.render).toSet
      def simplifyJson(j: ujson.Obj): Option[ujson.Obj] = {
        val map = j.value.flatMap {
          case (k, v: ujson.Obj) =>
            simplifyJson(v)
              .map((k, _))
              .orElse(Option.when(resolvedTaskLabels.contains(k)) { k -> v })
          case _ => ???
        }
        Option.when(map.nonEmpty)(ujson.Obj.from(map))
      }

      simplifyJson(json).getOrElse(ujson.Obj())
    }
  }

  def computeMetadata(
      tasks: Seq[NamedTask[?]]
  ): (SelectiveExecution.Metadata, Map[Task[?], ExecResult[Val]]) =
    SelectiveExecutionImpl.Metadata.compute(evaluator, tasks)
}
object SelectiveExecutionImpl {
  object Metadata {
    def compute(
        evaluator: Evaluator,
        tasks: Seq[NamedTask[?]]
    ): (SelectiveExecution.Metadata, Map[Task[?], ExecResult[Val]]) = {
      compute0(evaluator, PlanImpl.transitiveNamed(tasks))
    }

    def compute0(
        evaluator: Evaluator,
        transitiveNamed: Seq[NamedTask[?]]
    ): (SelectiveExecution.Metadata, Map[Task[?], ExecResult[Val]]) = {
      val results: Map[NamedTask[?], mill.api.Result[Val]] = transitiveNamed
        .collect { case task: InputImpl[_] =>
          val ctx = new mill.api.Ctx.Impl(
            args = Vector(),
            dest0 = () => null,
            log = evaluator.baseLogger,
            env = evaluator.env,
            reporter = _ => None,
            testReporter = mill.runner.api.DummyTestReporter,
            workspace = evaluator.workspace,
            systemExit = n => ???,
            fork = null,
            jobs = evaluator.effectiveThreadCount
          )
          task -> task.evaluate(ctx).map(Val(_))
        }
        .toMap

      val inputHashes = results.map {
        case (task, execResultVal) => (task.ctx.segments.render, execResultVal.get.value.hashCode)
      }
      new SelectiveExecution.Metadata(
        inputHashes,
        evaluator.codeSignatures
      ) -> results.map { case (k, v) => (k, ExecResult.Success(v.get)) }
    }
  }

}
