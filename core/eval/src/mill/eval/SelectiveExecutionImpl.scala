package mill.eval

import mill.api.daemon.internal.TestReporter
import mill.api.{ExecResult, Result, Val}
import mill.constants.OutFiles.OutFiles
import mill.api.SelectiveExecution.ChangedTasks
import mill.api.*
import mill.api.internal.EnvSignature
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
      classToTransitiveClasses,
      allTransitiveClassMethods,
      codeSignatures
    )
  }

  // The method-name table (`classToTransitiveClasses`/`allTransitiveClassMethods`) is a
  // reflective function of `transitiveNamed` alone, so callers that diff two `codeSignatures`
  // maps over the same `transitiveNamed` precompute it once and reuse it across both calls.
  private def computeHashCodeSignatures0(
      transitiveNamed: Seq[Task.Named[?]],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]],
      codeSignatures: Map[String, Int]
  ): Map[String, Int] = {
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
    def envSignature(m: SelectiveExecution.Metadata): EnvSignature =
      EnvSignature(m.millVersion, m.millJvmVersion, m.classLoaderSigHash)

    envSignature(oldHashes).diffReasonsTo(envSignature(newHashes)).headOption
      .map(reason =>
        DownstreamResult(allTasks.toSet, allTasks, globalInvalidationReason = Some(reason))
      )
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
        // Precompute the reflection-heavy method-name table once: it depends only on
        // `transitiveNamed`, so reuse it for both the old and new `codeSignatures` diff.
        val (classToTransitiveClasses, allTransitiveClassMethods) =
          CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)
        val changedCodeNames = diffMap(
          computeHashCodeSignatures0(
            transitiveNamed,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            oldHashes.codeSignatures
          ),
          computeHashCodeSignatures0(
            transitiveNamed,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            newHashes.codeSignatures
          )
        )
        val changedBuildOverrides = diffMap(
          oldHashes.buildOverrideSignatures,
          newHashes.buildOverrideSignatures
        )

        val changedRootTasks =
          (changedInputNames ++ changedCodeNames ++ changedBuildOverrides ++ oldHashes.forceRunTasks)
            .flatMap(namesToTasks.get(_): Option[Task[?]])

        val allNodes = breadthFirst(allTasks)(_.selectiveInputs)
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
          val ctx = EvaluatorImpl.inputTaskCtx(
            evaluator,
            evaluator.baseLogger,
            _ => None,
            TestReporter.DummyTestReporter
          )

          task -> task.evaluate(ctx).map(Val(_))
        }
        .toMap

      // Don't `.get` a failing `Task.Input`: that throws and aborts selective metadata
      // computation, which runs unguarded from `--watch` / `selective.prepare`. Hash a
      // stable representation of the failure and carry it as an `ExecResult.Failure`.
      val inputHashes = results.map {
        case (task, Result.Success(v)) => (task.ctx.segments.render, v.value.##)
        case (task, f: Result.Failure) => (task.ctx.segments.render, f.errorOpt.##)
      }
      val env = EnvSignature.current(evaluator.classLoaderSigHash)
      SelectiveExecution.Metadata.Computed(
        new SelectiveExecution.Metadata(
          inputHashes,
          evaluator.codeSignatures,
          // Hash the actual build override values from YAML files
          allBuildOverrides.map { case (k, located) => (k, located.value.value.hashCode) },
          forceRunTasks = Set(),
          millVersion = env.millVersion,
          millJvmVersion = env.millJvmVersion,
          classLoaderSigHash = env.classLoaderSigHash
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
