package mill.eval

import mill.api.daemon.internal.TestReporter
import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles
import mill.api.SelectiveExecution.ChangedTasks
import mill.api.*
import mill.exec.{CodeSigUtils, Execution, PlanImpl}
import mill.internal.SpanningForest
import mill.internal.SpanningForest.breadthFirst

private[mill] class SelectiveExecutionImpl(evaluator: Evaluator)
    extends mill.api.SelectiveExecution {

  def computeHashCodeSignatures(
      transitiveNamed: Seq[Task.Named[?]],
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
            namedTask = namedTask,
            classToTransitiveClasses = classToTransitiveClasses,
            allTransitiveClassMethods = allTransitiveClassMethods,
            codeSignatures = codeSignatures,
            constructorHashSignatures = constructorHashSignatures
          )
          .sum
      }
      .toMap
  }

  def computeDownstream(
      transitiveNamed: Seq[Task.Named[?]],
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
    val changedBuildOverrides = diffMap(
      oldHashes.buildOverrideSignatures,
      newHashes.buildOverrideSignatures
    )

    val changedRootTasks = (changedInputNames ++ changedCodeNames ++ changedBuildOverrides)
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
      upickle.write(metadata, indent = 2)
    )
  }

  def computeChangedTasks(
      tasks: Seq[String]
  ): Result[ChangedTasks] = {
    evaluator.resolveTasks(
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map { tasks =>
      computeChangedTasks0(tasks, SelectiveExecutionImpl.Metadata.compute(evaluator, tasks))
        // If we did not have the metadata, presume everything was changed.
        .getOrElse(ChangedTasks.all(tasks))
    }
  }

  /**
   * @return [[None]] when the metadata file is empty.
   * @note throws if the metadata file does not exist.
   */
  def computeChangedTasks0(
      tasks: Seq[Task.Named[?]],
      computedMetadata: SelectiveExecution.Metadata.Computed
  ): Option[ChangedTasks] = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)

    // We allow to clear the selective execution metadata to rerun all tasks.
    //
    // You would think that removing the file achieves the same result, however, blanking the file indicates that
    // this was intentional and you did not simply forgot to run `selective.prepare` beforehand.
    if (oldMetadataTxt == "") None
    else Some {
      val transitiveNamed = PlanImpl.transitiveNamed(tasks)
      val oldMetadata = upickle.read[SelectiveExecution.Metadata](oldMetadataTxt)
      val (changedRootTasks, downstreamTasks) =
        evaluator.selective.computeDownstream(
          transitiveNamed,
          oldMetadata,
          computedMetadata.metadata
        )

      ChangedTasks(
        tasks,
        changedRootTasks.collect { case n: Task.Named[_] => n },
        downstreamTasks.collect { case n: Task.Named[_] => n }
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

      val ( /*vertexToIndex*/ _, edgeIndices) =
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
      tasks: Seq[Task.Named[?]]
  ): SelectiveExecution.Metadata.Computed =
    SelectiveExecutionImpl.Metadata.compute(evaluator, tasks)
}
object SelectiveExecutionImpl {
  object Metadata {
    def compute(
        evaluator: Evaluator,
        tasks: Seq[Task.Named[?]]
    ): SelectiveExecution.Metadata.Computed = {
      compute0(evaluator, PlanImpl.transitiveNamed(tasks))
    }

    def compute0(
        evaluator: Evaluator,
        transitiveNamed: Seq[Task.Named[?]]
    ): SelectiveExecution.Metadata.Computed = {
      val allBuildOverrides =
        evaluator.buildOverrides ++
          transitiveNamed.flatMap(_.ctx.enclosingModule.moduleLoadBuildOverrides)

      val results: Map[Task.Named[?], mill.api.Result[Val]] = transitiveNamed
        .collect { case task: Task.Input[_] =>
          val ctx = new mill.api.TaskCtx.Impl(
            args = Vector(),
            dest0 = () => null,
            log = evaluator.baseLogger,
            env = evaluator.env,
            reporter = _ => None,
            testReporter = TestReporter.DummyTestReporter,
            workspace = evaluator.workspace,
            _systemExitWithReason = (reason, exitCode) =>
              throw Exception(s"systemExit called: reason=$reason, exitCode=$exitCode"),
            fork = null,
            jobs = evaluator.effectiveThreadCount,
            offline = evaluator.offline
          )
          val result: Result[Val] = allBuildOverrides.get(task.ctx.segments.render) match {
            case None => task.evaluate(ctx).map(Val(_))
            case Some(v) => mill.api.Result.create(Val(
                upickle.read(v)(
                  using
                  task.asInstanceOf[Task.Stub[_]].readWriterOpt.get.asInstanceOf[upickle.Reader[Any]]
                )
              ))
          }

          task -> result
        }
        .toMap

      val inputHashes = results.map {
        case (task, execResultVal) => (task.ctx.segments.render, execResultVal.get.value.##)
      }
      SelectiveExecution.Metadata.Computed(
        new SelectiveExecution.Metadata(
          inputHashes,
          evaluator.codeSignatures,
          allBuildOverrides.map { case (k, v) => (k, v.##) }
        ),
        results.map { case (k, v) => (k, ExecResult.Success(v.get)) }
      )
    }
  }

}
