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
        val allTaskStrings = rootInvalidatedTasks
          .collect { case t: Task.Named[?] => t.toString }
          .toSeq
          .sorted
        ujson.Obj(
          versionNode -> ujson.Obj.from(allTaskStrings.map(_ -> ujson.Obj()))
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
    transitiveNamed.flatMap { namedTask =>
      val taskName = namedTask.ctx.segments.render
      try {
        val (methodClass, encodedTaskName) = CodeSigUtils
          .methodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)
        Some(taskName -> Set(methodClass + "#" + encodedTaskName + "()"))
      } catch {
        case _: mill.api.MillException => None
      }
    }.toMap
  }

  /**
   * Merges the code signature spanning tree with the task invalidation tree.
   * Does a single traversal of the code signature tree, attaching task subtrees
   * at matching method nodes, and pruning branches with no matching tasks.
   */
  private def mergeCodeSignatureTree(
      baseTree: ujson.Obj,
      codeTree: ujson.Value,
      taskMethodSignatures: Map[String, Set[String]]
  ): ujson.Obj = {
    // Build reverse map: method signature prefix -> list of (taskName, subtree)
    val signatureToTasks: Map[String, Seq[(String, ujson.Value)]] = baseTree.value.toSeq
      .flatMap { case (taskName, subtree) =>
        taskMethodSignatures.getOrElse(taskName, Set.empty).map(sig => sig -> (taskName, subtree))
      }
      .groupMap(_._1)(_._2)

    val matchedTasks = collection.mutable.Set[String]()

    def findMatchingTasks(key: String): Seq[(String, ujson.Value)] = key match {
      case s"def $signature" =>
        signatureToTasks.iterator
          .filter { case (sig, _) => signature.startsWith(sig) }
          .flatMap(_._2)
          .filterNot { case (taskName, _) => matchedTasks.contains(taskName) }
          .toSeq
      case _ => Nil
    }

    // Single-pass traversal: recurse into children, attach matching tasks, prune empty branches
    def traverse(node: ujson.Value): Option[ujson.Obj] = {
      val result = ujson.Obj()
      for ((key, value) <- node.obj.value) {
        // First, recurse into children
        traverse(value).foreach(child => result(key) = child)

        // Then check if this node matches any tasks
        val tasks = findMatchingTasks(key)
        if (tasks.nonEmpty) {
          val tasksObj = result.value.get(key) match {
            case Some(obj: ujson.Obj) => obj
            case _ => ujson.Obj()
          }
          for ((taskName, subtree) <- tasks) {
            tasksObj(taskName) = subtree
            matchedTasks += taskName
          }
          result(key) = tasksObj
        }
      }
      if (result.value.nonEmpty) Some(result) else None
    }

    val merged = traverse(codeTree).getOrElse(ujson.Obj())

    // Add any tasks that weren't matched to a code path
    for ((taskName, subtree) <- baseTree.value if !matchedTasks.contains(taskName)) {
      merged(taskName) = subtree
    }

    merged
  }
}
