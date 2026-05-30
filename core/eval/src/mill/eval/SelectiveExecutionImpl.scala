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
    computeHashCodeSignatures0(
      transitiveNamed,
      codeSignatures,
      classToTransitiveClasses,
      allTransitiveClassMethods
    )
  }

  private def computeHashCodeSignatures0(
      transitiveNamed: Seq[Task.Named[?]],
      codeSignatures: Map[String, Int],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]]
  ): Map[String, Int] = {
    lazy val constructorHashSignatures = CodeSigUtils
      .constructorHashSignatures(codeSignatures)

    transitiveNamed
      .map { namedTask =>
        // Combine the per-task code signatures with the same order-independent combiner the
        // execution engine uses (`MurmurHash3.unorderedHash`, GroupExecution.scala), NOT a plain
        // `.sum`. Addition is a weak combiner that two compensating signature deltas can cancel
        // (e.g. one constructor hash +k while an accessor -k), masking a real code change so the
        // task is wrongly skipped.
        namedTask.ctx.segments.render -> scala.util.hashing.MurmurHash3.unorderedHash(
          CodeSigUtils.codeSigForTask(
            namedTask = namedTask,
            classToTransitiveClasses = classToTransitiveClasses,
            allTransitiveClassMethods = allTransitiveClassMethods,
            codeSignatures = codeSignatures,
            constructorHashSignatures = constructorHashSignatures
          )
        )
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
        // Precompute the per-class method/hierarchy tables ONCE — they are a pure function of
        // `transitiveNamed`, independent of the code signatures — and reuse them for both the old
        // and new signature sets, instead of redoing the reflection inside each call.
        val (classToTransitiveClasses, allTransitiveClassMethods) =
          CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)
        val changedCodeNames = diffMap(
          computeHashCodeSignatures0(
            transitiveNamed,
            oldHashes.codeSignatures,
            classToTransitiveClasses,
            allTransitiveClassMethods
          ),
          computeHashCodeSignatures0(
            transitiveNamed,
            newHashes.codeSignatures,
            classToTransitiveClasses,
            allTransitiveClassMethods
          )
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

  // Only keep named tasks in the graph to avoid anonymous tasks showing up in the tree.
  def upstreamNamedTasks(t: Task[?]) =
    breadthFirst(t.selectiveInputs) {
      case _: Task.Named[_] => Nil
      case t => t.selectiveInputs
    }.collect { case n: Task.Named[_] => n }.distinct

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

          val upstreamTaskEdges = downstreamNamed
            .map(t => (t: Task[?], upstreamNamedTasks(t)))
            .toMap

          val downstreamTaskEdges = SpanningForest.reverseEdges(upstreamTaskEdges)

          val paths = SpanningForest.breadthFirstWithPaths(
            result.changedRootTasks
          ) {
            case t: Task.Named[?] => downstreamTaskEdges.getOrElse(t, Nil)
            case _ => Nil
          }

          val pathTasks = paths
            .distinctBy(_.head)
            .filter(p => result.downstreamTasks.contains(p.head) && resolved.contains(p.head))
            .flatten
            .map(t => t: Task[?])
            .toSet

          val taskInvalidationReasons = result.globalInvalidationReason match {
            case Some(reason) =>
              pathTasks
                .collect { case n: Task.Named[_] => n.ctx.segments.render -> reason }
                .toMap
            case None => Map.empty[String, String]
          }

          val tree = InvalidationForest.buildInvalidationTree(
            upstreamTaskEdges0 = upstreamTaskEdges.collect {
              case (k, vs) if pathTasks.contains(k) => (k, vs.filter(pathTasks.contains(_)))
            },
            rootInvalidatedTasks = pathTasks,
            codeSignatureTree = evaluator.spanningInvalidationTree,
            taskInvalidationReasons = taskInvalidationReasons
          )

          tree
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
            _env = evaluator.env,
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

      // Hash the serialized-JSON form of each input value (mirroring
      // GroupExecution.getValueHash), NOT the in-memory `value.##`: the latter is
      // unstable across JVMs/runs (Map iteration order, identity hashCodes), so it
      // would spuriously flag unchanged inputs as changed and diverge from the disk
      // cache. Fall back to the in-memory hash if serialization throws.
      def inputValueHash(task: Task.Named[?], v: Val): Int =
        task.writerOpt match {
          case Some(w) =>
            try upickle.writeJs(v.value)(using w.asInstanceOf[upickle.Writer[Any]]).hashCode()
            catch { case scala.util.control.NonFatal(_) => v.## }
          case None => v.##
        }

      val inputHashes = results.map {
        case (task, Result.Success(v)) => (task.ctx.segments.render, inputValueHash(task, v))
        case (task, f: Result.Failure) =>
          // Do NOT `.get` a failing input: that throws (sys.error) and aborts
          // selective metadata computation, which runs unguarded from
          // `execute(selectiveExecution = true)` / `selective.prepare` / `-w`.
          // Hash a stable representation of the failure so a consistently-failing
          // input stays stable and any success<->failure transition (or changed
          // error) still invalidates its downstream cone.
          (task.ctx.segments.render, ("__mill_input_failure__", f.errorOpt).##)
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
        results.map { case (k, v) =>
          val execRes: ExecResult[Val] = v match {
            case Result.Success(value) => ExecResult.Success(value)
            case f: Result.Failure => ExecResult.Failure(f.error, Some(f))
          }
          (k, execRes)
        }
      )
    }
  }

}
