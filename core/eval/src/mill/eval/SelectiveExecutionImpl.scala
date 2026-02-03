package mill.eval

import mill.api.daemon.internal.TestReporter
import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles.OutFiles
import mill.api.SelectiveExecution.ChangedTasks
import mill.api.*
import mill.exec.PlanImpl
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
      downstreamTasks: Seq[Task[?]],
      // Global invalidation reason for selective execution (e.g., "mill-version-changed:OLD->NEW")
      globalInvalidationReason: Option[String] = None
  )

  def computeDownstream(
      transitiveNamed: Seq[Task.Named[?]],
      oldHashes: SelectiveExecution.Metadata,
      newHashes: SelectiveExecution.Metadata
  ): (Set[Task[?]], Seq[Task[?]]) = {
    val result = computeDownstreamDetailed(transitiveNamed, oldHashes, newHashes)
    (result.changedRootTasks, result.downstreamTasks)
  }

  def computeDownstreamDetailed(
      transitiveNamed: Seq[Task.Named[?]],
      oldHashes: SelectiveExecution.Metadata,
      newHashes: SelectiveExecution.Metadata
  ): DownstreamResult = {
    val allTasks = transitiveNamed.map(t => t: Task[?])
    def globalInvalidate(name: String, key: SelectiveExecution.Metadata => Any) = {
      Option.when(key(oldHashes) != key(newHashes)) {
        DownstreamResult(
          allTasks.toSet,
          allTasks,
          globalInvalidationReason = Some(s"$name:${key(oldHashes)}->${key(newHashes)}")
        )
      }
    }

    globalInvalidate("mill-version-changed", _.millVersion)
      .orElse(globalInvalidate("mill-jvm-version-changed", _.millJvmVersion))
      .orElse(globalInvalidate("classpath-changed", _.classLoaderSigHash))
      .getOrElse {
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

        val allNodes = breadthFirst(transitiveNamed.map(t => t: Task[?]))(_.selectiveInputs)
        val downstreamEdgeMap =
          SpanningForest.reverseEdges(allNodes.map(t => (t, t.selectiveInputs)))

        DownstreamResult(
          changedRootTasks,
          breadthFirst(changedRootTasks) { t =>
            downstreamEdgeMap.getOrElse(t.asInstanceOf[Task[Nothing]], Nil)
          }
        )
      }
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
  private def computeDownstreamResult0(
      tasks: Seq[Task.Named[?]],
      computedMetadata: SelectiveExecution.Metadata.Computed
  ): Option[DownstreamResult] = {
    val oldMetadataTxt = os.read(evaluator.outPath / OutFiles.millSelectiveExecution)

    // We allow to clear the selective execution metadata to rerun all tasks.
    //
    // You would think that removing the file achieves the same result, however, blanking the file indicates that
    // this was intentional and you did not simply forgot to run `selective.prepare` beforehand.
    if (oldMetadataTxt == "") None
    else Some {
      val transitiveNamed = SelectiveExecutionImpl.transitiveNamedSelective(tasks)
      val oldMetadata = upickle.read[SelectiveExecution.Metadata](oldMetadataTxt)
      computeDownstreamDetailed(transitiveNamed, oldMetadata, computedMetadata.metadata)
    }
  }

  def computeChangedTasks0(
      tasks: Seq[Task.Named[?]],
      computedMetadata: SelectiveExecution.Metadata.Computed
  ): Option[ChangedTasks] = {
    computeDownstreamResult0(tasks, computedMetadata).map { result =>
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

  private def pruneInvalidationTreeToSelected(
      tree: ujson.Value,
      selectedTaskNames: Set[String]
  ): ujson.Value = {
    def pruneObj(obj: ujson.Obj): Option[ujson.Obj] = {
      val kept = ujson.Obj()
      obj.value.foreach { case (key, value) =>
        value match {
          case childObj: ujson.Obj =>
            val prunedChild = pruneObj(childObj)
            if (selectedTaskNames.contains(key)) kept(key) = prunedChild.getOrElse(ujson.Obj())
            else prunedChild.foreach(kept(key) = _)
          case _ =>
            if (selectedTaskNames.contains(key)) kept(key) = ujson.Obj()
        }
      }

      Option.when(kept.value.nonEmpty) {
        ujson.Obj.from(kept.value.toArray.sortBy(_._1))
      }
    }

    tree match {
      case obj: ujson.Obj => pruneObj(obj).getOrElse(ujson.Obj())
      case _ => ujson.Obj()
    }
  }

  def namedUpstreamEdges(tasks: Seq[Task[?]]): Map[Task.Named[?], Seq[Task.Named[?]]] = {
    val namedTasks = tasks.collect { case n: Task.Named[_] => n }

    def collectNamedInputs(start: Seq[Task[?]]): Seq[Task.Named[?]] = {
      breadthFirst(start) {
        case _: Task.Named[_] => Nil
        case t => t.selectiveInputs
      }.collect { case n: Task.Named[_] => n }
        .distinct
        .sortBy(_.ctx.segments.render)
    }

    namedTasks.map(t => t -> collectNamedInputs(t.selectiveInputs)).toMap
  }

  def resolveTree(tasks: Seq[String]): Result[ujson.Value] = {
    evaluator.resolveTasks(
      tasks,
      SelectMode.Separated,
      evaluator.allowPositionalCommandArgs
    ).map { resolved =>
      computeDownstreamResult0(resolved, computeMetadata(resolved)) match {
        case None => ujson.Obj()
        case Some(result) =>
          val downstreamNamed = result.downstreamTasks.collect { case n: Task.Named[_] => n }
          val selectedNamed = resolved.filter(downstreamNamed.toSet.contains)
          val selectedTaskNames = selectedNamed.map(_.ctx.segments.render).toSet

          if (selectedNamed.isEmpty) ujson.Obj()
          else {
            val transitiveTasks = SelectiveExecutionImpl.transitiveTasksSelective(downstreamNamed)

            val upstreamTaskEdges0 = namedUpstreamEdges(transitiveTasks)

            // Prune to only branches that lead to a selected task.
            val keepTasks = breadthFirst(selectedNamed.map(t => t: Task[?])) { t =>
              upstreamTaskEdges0.getOrElse(t.asInstanceOf[Task.Named[_]], Nil)
            }.toSet

            val upstreamTaskEdgesNamed = upstreamTaskEdges0.collect {
              case (task, inputs) if keepTasks.contains(task) =>
                task -> inputs.filter(keepTasks.contains)
            }

            val upstreamTaskEdges: Map[Task[?], Seq[Task[?]]] =
              upstreamTaskEdgesNamed.view.map { case (k, v) => (k: Task[?]) -> v }.toMap

            val rootInvalidatedTasks = result.changedRootTasks.collect {
              case n: Task.Named[_] if keepTasks.contains(n) => n: Task[?]
            }

            // For selective execution, use global invalidation reason for all root tasks
            val taskInvalidationReasons = result.globalInvalidationReason match {
              case Some(reason) =>
                rootInvalidatedTasks
                  .collect { case n: Task.Named[_] => n.ctx.segments.render -> reason }
                  .toMap

              case None => Map.empty[String, String]
            }

            val tree = InvalidationForest.buildInvalidationTree(
              upstreamTaskEdges0 = upstreamTaskEdges,
              rootInvalidatedTasks = rootInvalidatedTasks,
              codeSignatureTree = evaluator.spanningInvalidationTree,
              taskInvalidationReasons = taskInvalidationReasons
            )

            pruneInvalidationTreeToSelected(tree, selectedTaskNames)
          }
      }
    }
  }

  def computeMetadata(
      tasks: Seq[Task.Named[?]]
  ): SelectiveExecution.Metadata.Computed =
    SelectiveExecutionImpl.Metadata.compute0(
      evaluator,
      SelectiveExecutionImpl.transitiveNamedSelective(tasks)
    )
}

object SelectiveExecutionImpl {
  def transitiveTasksSelective(tasks: Seq[Task[?]]): IndexedSeq[Task[?]] =
    PlanImpl.transitiveNodes(tasks)(_.selectiveInputs)

  def transitiveNamedSelective(tasks: Seq[Task[?]]): Seq[Task.Named[?]] =
    transitiveTasksSelective(tasks).collect { case t: Task.Named[?] => t }

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
          millJvmVersion = sys.props("java.version"),
          classLoaderSigHash = evaluator.classLoaderSigHash
        ),
        results.map { case (k, v) => (k, ExecResult.Success(v.get)) }
      )
    }
  }

}
