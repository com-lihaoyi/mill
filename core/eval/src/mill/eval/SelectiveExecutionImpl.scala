package mill.eval

import mill.api.daemon.internal.TestReporter
import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles.OutFiles
import mill.api.SelectiveExecution.ChangedTasks
import mill.api.*
import mill.exec.{CodeSigUtils, Execution, PlanImpl}
import mill.internal.SpanningForest
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
      millVersionChanged: Option[(String, String)],
      millJvmVersionChanged: Option[(String, String)]
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
    def versionChanged(oldV: String, newV: String): Option[(String, String)] =
      Option.when(oldV.nonEmpty && oldV != newV)((oldV, newV))

    val millVersionChanged = versionChanged(oldHashes.millVersion, newHashes.millVersion)
    val millJvmVersionChanged = versionChanged(oldHashes.millJvmVersion, newHashes.millJvmVersion)

    // If either version changed, treat all tasks as changed
    if (millVersionChanged.isDefined || millJvmVersionChanged.isDefined) {
      val allTasks = transitiveNamed.map(t => t: Task[?]).toSet
      return DownstreamResult(
        allTasks,
        transitiveNamed.map(t => t: Task[Any]),
        millVersionChanged = millVersionChanged,
        millJvmVersionChanged = millJvmVersionChanged
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
      millVersionChanged = None,
      millJvmVersionChanged = None
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
        .getOrElse(ChangedTasks.all(
          tasks,
          millVersionChanged = Some(("", mill.constants.BuildInfo.millVersion))
        ))
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
        result.downstreamTasks.collect { case n: Task.Named[_] => n },
        millVersionChanged = result.millVersionChanged,
        millJvmVersionChanged = result.millJvmVersionChanged
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
    for (changedTasks <- this.computeChangedTasks(tasks)) yield {
      val plan = PlanImpl.plan(Seq.from(changedTasks.downstreamTasks))

      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)
      val reverseInterGroupDeps = SpanningForest.reverseEdges(
        interGroupDeps.toSeq.sortBy(_._1.toString)
      )

      // Build task edges map (task name -> downstream task names)
      val taskEdges: Map[String, Seq[String]] = reverseInterGroupDeps
        .view
        .map { case (k, vs) => k.toString -> vs.map(_.toString) }
        .toMap

      val interestingTasks = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
      val resolvedTaskLabels = changedTasks.resolved.map(_.ctx.segments.render).toSet

      // Compute class metadata for method signature resolution
      val transitiveNamed = PlanImpl.transitiveNamed(changedTasks.downstreamTasks)
      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

      SpanningForest.buildInvalidationTree(
        taskEdges = taskEdges,
        interestingTasks = interestingTasks,
        transitiveNamed = transitiveNamed,
        classToTransitiveClasses = classToTransitiveClasses,
        allTransitiveClassMethods = allTransitiveClassMethods,
        codeSignatureTree = evaluator.spanningInvalidationTree,
        millVersionChanged = changedTasks.millVersionChanged.orElse(evaluator.millVersionChanged),
        millJvmVersionChanged = changedTasks.millJvmVersionChanged.orElse(evaluator.millJvmVersionChanged),
        resolvedTasks = Some(resolvedTaskLabels)
      )
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

      val transitiveNamedMap = transitiveNamed.map(t => (t.ctx.segments.render, t)).toMap
      val inputHashes = results.map {
        case (task, execResultVal) => (task.ctx.segments.render, execResultVal.get.value.##)
      }
      SelectiveExecution.Metadata.Computed(
        new SelectiveExecution.Metadata(
          inputHashes,
          evaluator.codeSignatures,
          for {
            (k, _) <- allBuildOverrides
            // Make sure we deserialize the actual value to hash, rather than hashing the JSON,
            // since a JSON string may deserialize into a `PathRef` that changes depending on
            // the files and folders on disk
            value <- transitiveNamedMap.get(k)
          } yield (k, value.##),
          forceRunTasks = Set(),
          millVersion = mill.constants.BuildInfo.millVersion,
          millJvmVersion = sys.props("java.version")
        ),
        results.map { case (k, v) => (k, ExecResult.Success(v.get)) }
      )
    }
  }

}
