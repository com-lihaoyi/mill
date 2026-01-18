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
      invalidationReasons: Map[String, SelectiveExecution.InvalidationReason],
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
    import SelectiveExecution.InvalidationReason

    def versionChanged(oldV: String, newV: String): Option[(String, String)] =
      Option.when(oldV.nonEmpty && oldV != newV)((oldV, newV))

    val millVersionChanged = versionChanged(oldHashes.millVersion, newHashes.millVersion)
    val millJvmVersionChanged = versionChanged(oldHashes.millJvmVersion, newHashes.millJvmVersion)

    // If either version changed, treat all tasks as changed
    if (millVersionChanged.isDefined || millJvmVersionChanged.isDefined) {
      val allTasks = transitiveNamed.map(t => t: Task[?]).toSet
      val reason = millVersionChanged match {
        case Some((old, newV)) => InvalidationReason.MillVersionChanged(old, newV)
        case None =>
          val (old, newV) = millJvmVersionChanged.get
          InvalidationReason.MillJvmVersionChanged(old, newV)
      }
      return DownstreamResult(
        allTasks,
        transitiveNamed.map(t => t: Task[Any]),
        transitiveNamed.map(t => t.ctx.segments.render -> reason).toMap,
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

    // Build the invalidation reasons map
    val invalidationReasons: Map[String, InvalidationReason] =
      changedInputNames.map(_ -> InvalidationReason.InputChanged).toMap ++
        changedCodeNames.map(_ -> InvalidationReason.CodeChanged).toMap ++
        changedBuildOverrides.map(_ -> InvalidationReason.BuildOverrideChanged).toMap ++
        oldHashes.forceRunTasks.map(_ -> InvalidationReason.ForcedRun).toMap

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
      invalidationReasons,
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
          SelectiveExecution.InvalidationReason.MillVersionChanged("", mill.constants.BuildInfo.millVersion)
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
        result.invalidationReasons,
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
    import SelectiveExecution.InvalidationReason

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

      val simplifiedJson = simplifyJson(json).getOrElse(ujson.Obj())

      // Build a tree that shows invalidation reasons at the root
      if (changedTasks.millVersionChanged.isDefined || changedTasks.millJvmVersionChanged.isDefined) {
        // All tasks invalidated due to mill-version or mill-jvm-version change
        val result = ujson.Obj()
        changedTasks.millVersionChanged.foreach { case (oldV, newV) =>
          result(s"mill-version-changed:$oldV->$newV") = simplifiedJson
        }
        changedTasks.millJvmVersionChanged.foreach { case (oldV, newV) =>
          result(s"mill-jvm-version-changed:$oldV->$newV") = simplifiedJson
        }
        result
      } else if (changedTasks.invalidationReasons.nonEmpty) {
        // Group root tasks by their invalidation reason
        val tasksByReason = changedTasks.invalidationReasons.groupBy(_._2).map {
          case (reason, tasks) => (reason, tasks.keys.toSeq.sorted)
        }

        // For code changes, try to load the spanningInvalidationTree.json
        val codeChangedTasks = tasksByReason.getOrElse(InvalidationReason.CodeChanged, Seq.empty)
        val codeChangeTree: ujson.Value =
          if (codeChangedTasks.isEmpty) ujson.Obj()
          else {
            // Build a base tree from code-changed tasks with their downstream tasks
            val codeChangeTaskTree = ujson.Obj.from(codeChangedTasks.sorted.flatMap { taskName =>
              // Find the downstream tasks for this task in simplifiedJson
              def getSubtreeForTask(name: String, json: ujson.Obj): Option[(String, ujson.Value)] = {
                json.value.get(name) match {
                  case Some(subtree) => Some(name -> subtree)
                  case None =>
                    // Search recursively for the task
                    json.value.view.flatMap {
                      case (_, v: ujson.Obj) => getSubtreeForTask(name, v)
                      case _ => None
                    }.headOption
                }
              }
              getSubtreeForTask(taskName, simplifiedJson).orElse(Some(taskName -> ujson.Obj()))
            })

            // Try to read the spanningInvalidationTree.json from the mill-build codeSignaturesAndSpanningTree
            val spanningTreePath =
              evaluator.outPath / "mill-build" / "codeSignaturesAndSpanningTree.dest" / "current" / "spanningInvalidationTree.json"

            val spanningTree: Option[ujson.Value] =
              if (os.exists(spanningTreePath)) {
                try Some(ujson.read(os.read(spanningTreePath)))
                catch { case _: Exception => None }
              } else None

            // Use the shared utility to merge code paths with task tree
            spanningTree match {
              case Some(tree) => SpanningForest.mergeCodeSignatureTree(codeChangeTaskTree, tree)
              case None => codeChangeTaskTree
            }
          }

        // Build sub-trees for each task under each reason
        def buildSubTree(taskNames: Seq[String]): ujson.Obj = {
          val taskNameSet = taskNames.toSet
          def filterTree(j: ujson.Obj): ujson.Obj = {
            ujson.Obj.from(j.value.flatMap {
              case (k, v: ujson.Obj) =>
                val filtered = filterTree(v)
                if (taskNameSet.contains(k) || filtered.value.nonEmpty) Some(k -> filtered)
                else None
              case other => Some(other)
            })
          }
          filterTree(simplifiedJson)
        }

        val result = ujson.Obj()

        // Add input changes
        tasksByReason.get(InvalidationReason.InputChanged).foreach { tasks =>
          result("<input changed>") = buildSubTree(tasks)
        }

        // Add code changes with the detailed spanning tree merged directly into result
        // (showing the method/call chain from spanningInvalidationTree.json)
        if (codeChangedTasks.nonEmpty) {
          codeChangeTree match {
            case obj: ujson.Obj => obj.value.foreach { case (k, v) => result(k) = v }
            case _ => // ignore non-object trees
          }
        }

        // Add build override changes
        tasksByReason.get(InvalidationReason.BuildOverrideChanged).foreach { tasks =>
          result("<build override changed>") = buildSubTree(tasks)
        }

        // Add forced runs
        tasksByReason.get(InvalidationReason.ForcedRun).foreach { tasks =>
          result("<forced run>") = buildSubTree(tasks)
        }

        result
      } else {
        simplifiedJson
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
