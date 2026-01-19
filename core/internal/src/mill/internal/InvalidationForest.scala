package mill.internal

import mill.api.Task
import mill.api.daemon.VersionState

/**
 * Builds invalidation trees for displaying task invalidation reasons.
 * Used by `mill-invalidation-tree.json` and `selective.resolveTree`.
 */
object InvalidationForest {

  /**
   * Computes a version change node from previous versions.
   * Returns a formatted string like "mill-version-changed:0.12.0->0.12.1" for display,
   * or combines both if mill and JVM versions changed.
   */
  private def computeVersionChangeNode(previousVersions: Option[VersionState]): Option[String] = {
    previousVersions.flatMap { vs =>
      def changeTag(tag: String, curr: String, prev: String) = Option.when(curr != prev)(
        s"$tag:$prev->$curr"
      )

      val parts =
        changeTag("mill-version-changed", mill.constants.BuildInfo.millVersion, vs.millVersion) ++
        changeTag("mill-jvm-version-changed", sys.props("java.version"), vs.millJvmVersion)

      Option.when(parts.nonEmpty)(parts.mkString(","))
    }
  }

  /**
   * Builds an invalidation tree that combines:
   * - Task dependency spanning forest
   * - Code signature spanning tree (showing method call chains)
   * - Version change nodes (mill-version-changed, mill-jvm-version-changed)
   */
  def buildInvalidationTree(
      // The edges of the task graph, simplified to only consider task groups headed
      // by named tasks so as to ignore the misc anonymous tasks used internally
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      rootInvalidatedTasks: Set[Task[?]],
      // Other ways that tasks can be invalidated - due to code changes or due to mill/jvm
      // version changes - so we can include them as causal nodes in the invalidation forest
      codeSignatureTree: Option[String],
      previousVersions: Option[VersionState]
  ): ujson.Obj = {
    // All named tasks are group heads
    val transitiveNamed = interGroupDeps.keys.collect { case t: Task.Named[?] => t }.toSeq

    computeVersionChangeNode(previousVersions) match {
      // If Mill or JVM version changed, everything is invalidated, so return a simple tree
      // with version change as root and all tasks as direct children
      // (no need for spanning forest computation)
      case Some(versionNode) =>
        ujson.Obj(
          versionNode ->
            ujson.Obj.from(transitiveNamed.map(_.toString).sorted.map(_ -> ujson.Obj()))
        )

      case None =>
        val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)
        // Only include edges from root invalidated tasks
        val filteredReverseInterGroupDeps =
          reverseInterGroupDeps.view.filterKeys(rootInvalidatedTasks).toMap

        val taskEdges: Map[String, Seq[String]] = filteredReverseInterGroupDeps
          .view
          .map { case (k, vs) => k.toString -> vs.map(_.toString) }
          .toMap

        val rootInvalidatedTaskStrings = rootInvalidatedTasks.map(_.toString)

        val allNodes = (taskEdges.keys ++ taskEdges.values.flatten ++ rootInvalidatedTaskStrings)
          .toArray
          .distinct
          .sorted

        val taskToIndex = allNodes.zipWithIndex.toMap
        val indexEdges = allNodes.map(t => taskEdges.getOrElse(t, Nil).flatMap(taskToIndex.get).toArray)

        val rootIndices = rootInvalidatedTaskStrings.flatMap(taskToIndex.get)

        val baseForest = SpanningForest(indexEdges, rootIndices, limitToImportantVertices = true)
        val baseTree = SpanningForest.spanningTreeToJsonTree(baseForest, allNodes(_))

        codeSignatureTree.map(ujson.read(_).obj) match {
          case Some(codeSigTree) =>
            val (classToTransitiveClasses, allTransitiveClassMethods) =
              CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

            val taskMethodSignatures = methodSignaturePrefixesForTasks(
              transitiveNamed,
              classToTransitiveClasses,
              allTransitiveClassMethods
            )
            mergeCodeSignatureTree(baseTree, codeSigTree, taskMethodSignatures)
          case None => baseTree
        }
    }
  }

  /**
   * Computes the method signature prefixes for tasks.
   * Returns a map from task name (e.g., "foo.compile") to a set of method signature prefixes
   * that should match entries in the code signature spanning tree.
   */
  private def methodSignaturePrefixesForTasks(
      transitiveNamed: Seq[Task.Named[?]],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]]
  ): Map[String, Set[String]] = {
    transitiveNamed
      .map { namedTask =>
        val taskName = namedTask.ctx.segments.render
        val (methodClass, encodedTaskName) = CodeSigUtils
          .methodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)
        taskName -> Set(methodClass + "#" + encodedTaskName + "()")
      }
      .toMap
  }

  /**
   * Merges the code signature spanning tree with the task invalidation tree.
   * For each root task with a code path, wraps it (and its children) with the code path.
   * Preserves task dependency structure (nested tasks remain nested).
   */
  private def mergeCodeSignatureTree(
      baseTree: ujson.Obj,
      spanningTree: ujson.Value,
      taskMethodSignatures: Map[String, Set[String]]
  ): ujson.Obj = {
    val result = ujson.Obj()

    // Group root tasks by their code path
    val tasksByPath = collection.mutable.Map[Seq[String], collection.mutable.Buffer[(String, ujson.Value)]]()
    val tasksWithoutPath = collection.mutable.Buffer[(String, ujson.Value)]()

    for ((taskName, subtree) <- baseTree.value) {
      findPathForTask(taskName, spanningTree, taskMethodSignatures) match {
        case Some(path) if path.nonEmpty =>
          tasksByPath.getOrElseUpdate(path, collection.mutable.Buffer()) += ((taskName, subtree))

        case _ => tasksWithoutPath += ((taskName, subtree))
      }
    }

    // For each unique code path, create a single entry with all tasks underneath
    for ((path, tasks) <- tasksByPath) {
      val combinedTasks = ujson.Obj.from(tasks)

      for ((k, v) <- wrapWithPath(path, combinedTasks).obj.value) {
        result.value.get(k) match {
          case Some(existing: ujson.Obj) => deepMerge(existing, v.obj)
          case _ => result(k) = v
        }
      }
    }

    for ((taskName, subtree) <- tasksWithoutPath) result(taskName) = subtree

    result
  }

  /**
   * Finds the path of def/call nodes in the spanning tree that leads to a
   * method signature matching the given task name.
   */
  private def findPathForTask(
      taskName: String,
      tree: ujson.Value,
      taskMethodSignatures: Map[String, Set[String]]
  ): Option[Seq[String]] = {
    val signatures = taskMethodSignatures.getOrElse(taskName, Set.empty)
    val classNames = signatures.flatMap(_.split('#').headOption)

    def matchesTask(key: String): Boolean = key match{
      case s"def $signature" =>
        val directMatch = signatures.exists(sig => signature.startsWith(sig))
        val classMatch = classNames.exists { cls =>
          signature.endsWith(cls) || signature.startsWith(cls + "#")
        }
        directMatch || classMatch
      case _ => false
    }

    def findDeepest(node: ujson.Value, currentPath: Seq[String]): Option[Seq[String]] = node match {
      case obj: ujson.Obj =>
        obj.value.foldLeft(Option.empty[Seq[String]]) { case (acc, (key, value)) =>
          val newPath = currentPath :+ key
          val childMatch = findDeepest(value, newPath)
          // Prefer deeper matches, then current match, then previous accumulator
          childMatch.orElse(Option.when(matchesTask(key))(newPath)).orElse(acc)
        }
      case _ => None
    }

    findDeepest(tree, Seq.empty)
  }

  private def wrapWithPath(path: Seq[String], content: ujson.Value): ujson.Value =
    path.foldRight(content)((key, inner) => ujson.Obj(key -> inner))

  private def deepMerge(target: ujson.Obj, source: ujson.Obj): Unit = {
    for ((k, v) <- source.value) {
      target.value.get(k) match {
        case Some(existing: ujson.Obj) => deepMerge(existing, v.obj)
        case _ => target(k) = v
      }
    }
  }
}
