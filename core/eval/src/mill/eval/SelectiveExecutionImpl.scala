package mill.eval

import mill.api.daemon.internal.TestReporter
import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles.OutFiles
import mill.api.SelectiveExecution.ChangedTasks
import mill.api.*
import mill.exec.{Execution, PlanImpl}
import mill.internal.{CodeSigUtils, InvalidationForest, SpanningForest}
import mill.internal.SpanningForest.breadthFirst

class SelectiveExecutionImpl(evaluator: Evaluator)
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

  case class DownstreamResult(
      changedRootTasks: Set[Task[?]],
      downstreamTasks: Seq[Task[Any]],
      // Version change info for selective execution display
      versionChanged: Option[(String, String, String, String)]
  )

  def computeDownstream(
      transitiveNamed: Seq[Task.Named[?]],
      oldHashes: SelectiveExecution.Metadata,
      newHashes: SelectiveExecution.Metadata
  ): (Set[Task[?]], Seq[Task[Any]]) = {
    val result = computeDownstreamDetailed(transitiveNamed, oldHashes, newHashes)
    (result.changedRootTasks, result.downstreamTasks)
  }

  def computeDownstreamDetailed(
      transitiveNamed: Seq[Task.Named[?]],
      oldHashes: SelectiveExecution.Metadata,
      newHashes: SelectiveExecution.Metadata
  ): DownstreamResult = {
    // For selective execution, we still need to check version changes at the metadata level
    // This is different from per-task cache invalidation in loadCachedJson
    val millVersionChanged = oldHashes.millVersion != newHashes.millVersion
    val jvmVersionChanged = oldHashes.millJvmVersion != newHashes.millJvmVersion

    // If either version changed, treat all tasks as changed for selective execution
    if (millVersionChanged || jvmVersionChanged) {
      val allTasks = transitiveNamed.map(t => t: Task[?]).toSet
      return DownstreamResult(
        allTasks,
        transitiveNamed.map(t => t: Task[Any]),
        versionChanged = Some((
          oldHashes.millVersion,
          newHashes.millVersion,
          oldHashes.millJvmVersion,
          newHashes.millJvmVersion
        ))
      )
    }

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

    val changedRootTasks =
      (changedInputNames ++ changedCodeNames ++ changedBuildOverrides ++ oldHashes.forceRunTasks)
        .flatMap(namesToTasks.get(_): Option[Task[?]])

    val allNodes = breadthFirst(transitiveNamed.map(t => t: Task[?]))(_.inputs)
    val downstreamEdgeMap = SpanningForest.reverseEdges(allNodes.map(t => (t, t.inputs)))

    DownstreamResult(
      changedRootTasks,
      breadthFirst(changedRootTasks) { t =>
        downstreamEdgeMap.getOrElse(t.asInstanceOf[Task[Nothing]], Nil)
      },
      versionChanged = None
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
      computeChangedTasks0(tasks, computeMetadata(tasks))
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
      val result = computeDownstreamDetailed(
        transitiveNamed,
        oldMetadata,
        computedMetadata.metadata
      )

      ChangedTasks(
        tasks,
        result.changedRootTasks.collect { case n: Task.Named[_] => n },
        result.downstreamTasks.collect { case n: Task.Named[_] => n }
      )
    }
  }

  def resolve0(tasks: Seq[String]): Result[Array[String]] = {
    resolveTasks0(tasks).map(_.map(_.ctx.segments.render))
  }
  def resolveTasks0(tasks: Seq[String]): Result[Array[Task.Named[?]]] = {
    for {
      (resolved, changedTasks) <-
        evaluator.resolveTasks(tasks, SelectMode.Separated).zip(this.computeChangedTasks(tasks))
    } yield {
      val downstreamTasksRendered = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet

      resolved
        .filter(t => downstreamTasksRendered.contains(t.ctx.segments.render))
        .toArray
    }
  }

  def resolveChanged(tasks: Seq[String]): Result[Seq[String]] = {
    for (changedTasks <- this.computeChangedTasks(tasks)) yield {
      changedTasks.changedRootTasks.map(_.ctx.segments.render).toSeq.sorted
    }
  }

  def resolveTree(tasks: Seq[String]): Result[ujson.Value] = {
    evaluator.resolveTasks(
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map { resolved =>
      val computedMetadata = computeMetadata(resolved)
      val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)

      if (oldMetadataTxt == "") {
        // No metadata, return empty tree
        ujson.Obj()
      } else {
        val transitiveNamed = PlanImpl.transitiveNamed(resolved)
        val oldMetadata = upickle.read[SelectiveExecution.Metadata](oldMetadataTxt)
        val result = computeDownstreamDetailed(
          transitiveNamed,
          oldMetadata,
          computedMetadata.metadata
        )

        val plan = PlanImpl.plan(Seq.from(result.downstreamTasks.collect { case n: Task.Named[_] => n }))
        val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)

        InvalidationForest.buildInvalidationTree(
          upstreamTaskEdges0 = interGroupDeps,
          rootInvalidatedTasks = result.changedRootTasks.collect { case n: Task.Named[_] => n: Task[?] },
          codeSignatureTree = evaluator.spanningInvalidationTree,
          versionChanged = result.versionChanged
        )
      }
    }
  }

  def computeMetadata(
      tasks: Seq[Task.Named[?]]
  ): SelectiveExecution.Metadata.Computed =
    SelectiveExecutionImpl.Metadata.compute0(evaluator, PlanImpl.transitiveNamed(tasks))
}

object SelectiveExecutionImpl {
  object Metadata {
    def compute0(
        evaluator: Evaluator,
        transitiveNamed: Seq[Task.Named[?]]
    ): SelectiveExecution.Metadata.Computed = {
      val allBuildOverrides =
        evaluator.staticBuildOverrides ++
          transitiveNamed.flatMap(_.ctx.enclosingModule.moduleDynamicBuildOverrides)

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
            offline = evaluator.offline,
            useFileLocks = evaluator.useFileLocks
          )

          task -> task.evaluate(ctx).map(Val(_))
        }
        .toMap

      val inputHashes = results.map {
        case (task, execResultVal) => (task.ctx.segments.render, execResultVal.get.value.##)
      }
      SelectiveExecution.Metadata.Computed(
        new SelectiveExecution.Metadata(
          inputHashes,
          evaluator.codeSignatures,
          // Hash the actual build override values from YAML files
          allBuildOverrides.map { case (k, located) => (k, located.value.value.hashCode) },
          forceRunTasks = Set(),
          millVersion = mill.constants.BuildInfo.millVersion,
          millJvmVersion = sys.props("java.version")
        ),
        results.map { case (k, v) => (k, ExecResult.Success(v.get)) }
      )
    }
  }

}
