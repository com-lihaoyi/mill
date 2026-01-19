package mill.internal

import scala.collection.mutable

/**
 * Algorithm to compute the minimal spanning forest of a directed acyclic graph
 * that covers a particular subset of [[importantVertices]] (a "Steiner Forest"),
 * minimizing the maximum height of the resultant trees. When multiple solutions
 * exist with the same height, one chosen is arbitrarily. (This is much simpler
 * than the "real" algorithm which aims to minimize the sum of edge/vertex weights)
 *
 * Returns the forest as a [[Node]] structure with the top-level node containing
 * the roots of the forest
 */
object SpanningForest {

  def graphMapToIndices[T](
      vertices: Iterable[T],
      edges: Map[T, Seq[T]]
  ): (Map[T, Int], Array[Array[Int]]) = {
    val vertexToIndex = vertices.zipWithIndex.toMap
    val edgeIndices = vertices
      .map(t => edges.getOrElse(t, Nil).flatMap(vertexToIndex.get(_)).toArray)
      .toArray

    (vertexToIndex, edgeIndices)
  }

  def writeJsonFile(
      path: os.Path,
      indexEdges: Array[Array[Int]],
      interestingIndices: Set[Int],
      render: Int => String
  ): Unit = {
    val json = writeJson(indexEdges, interestingIndices, render).render(indent = 2)
    os.write.over(path, json)
  }

  def writeJson(
      indexEdges: Array[Array[Int]],
      interestingIndices: Set[Int],
      render: Int => String
  ): ujson.Obj = {
    val forest = SpanningForest(indexEdges, interestingIndices, true)
    SpanningForest.spanningTreeToJsonTree(forest, render)
  }

  def spanningTreeToJsonTree(node: SpanningForest.Node, stringify: Int => String): ujson.Obj = {
    ujson.Obj.from(
      node.values.map { case (k, v) => stringify(k) -> spanningTreeToJsonTree(v, stringify) }
    )
  }

  case class Node(values: mutable.Map[Int, Node] = mutable.Map())
  def apply(
      indexGraphEdges: Array[Array[Int]],
      importantVertices: Set[Int],
      limitToImportantVertices: Boolean
  ): Node = {
    // Find all importantVertices which are "roots" with no incoming edges
    // from other importantVertices
    val destinations = importantVertices.flatMap(indexGraphEdges(_))
    val rootChangedNodeIndices = importantVertices.filter(!destinations.contains(_))

    // Prepare a mutable tree structure that we will return, pre-populated with
    // just the first level of nodes from the `rootChangedNodeIndices`, as well
    // as a `nodeMapping` to let us easily take any node index and directly look
    // up the node in the tree
    val nodeMapping = rootChangedNodeIndices.map((_, Node())).to(mutable.Map)
    val spanningForest = Node(nodeMapping.clone())

    // Do a breadth first search from the `rootChangedNodeIndices` across the
    // reverse edges of the graph to build up the spanning forest
    breadthFirst(rootChangedNodeIndices) { index =>
      // needed to add explicit type for Scala 3.5.0-RC6
      val nextIndices = indexGraphEdges(index)
        .filter(e => !limitToImportantVertices || importantVertices(e))

      // We build up the spanningForest during a normal breadth first search,
      // using the `nodeMapping` to quickly find a vertice's tree node so we
      // can add children to it. We need to duplicate the `seen.contains` logic
      // in `!nodeMapping.contains`, because `breadthFirst` does not expose it
      for (nextIndex <- nextIndices if !nodeMapping.contains(nextIndex)) {
        val node = Node()
        nodeMapping(nextIndex) = node
        nodeMapping(index).values(nextIndex) = node
      }
      nextIndices
    }
    spanningForest
  }

  def breadthFirst[T](start: IterableOnce[T])(edges: T => IterableOnce[T]): Seq[T] = {
    val seen = collection.mutable.Set.empty[T]
    val seenList = collection.mutable.Buffer.empty[T]
    val queued = collection.mutable.Queue.from(start)

    while (queued.nonEmpty) {
      val current = queued.dequeue()
      seenList.append(current)

      for (next <- edges(current).iterator) {
        if (!seen.contains(next)) {
          seen.add(next)
          queued.enqueue(next)
        }
      }
    }
    seenList.toSeq
  }

  def reverseEdges[T, V](edges: Iterable[(T, Iterable[V])]): Map[V, Vector[T]] = {
    val flatEdges = edges.iterator.flatMap { case (k, vs) => vs.map(_ -> k) }.toVector
    flatEdges.groupMap(_._1)(_._2).map { case (k, v) => (k, v.toSeq) }.toMap
  }

  /**
   * Builds an invalidation tree that combines:
   * - Task dependency spanning forest
   * - Code signature spanning tree (showing method call chains)
   * - Version change nodes (mill-version-changed, mill-jvm-version-changed)
   * - Invalidation reason grouping (input changed, code changed, etc.)
   *
   * @param taskEdges Map from task name to downstream task names
   * @param interestingTasks All tasks that should appear in the tree
   * @param codeSignatureTree Optional code signature spanning tree (JSON string to avoid classloader issues)
   * @param taskMethodSignatures Optional map from task name to its method signature prefixes
   *                             (computed via CodeSigUtils.codeSigForTask). Used to match tasks
   *                             to their code paths in the spanning tree.
   * @param millVersionChanged Optional (oldVersion, newVersion) if mill version changed
   * @param millJvmVersionChanged Optional (oldVersion, newVersion) if mill JVM version changed
   * @param invalidationReasons Optional map from task name to reason string (e.g. "<input changed>")
   * @param resolvedTasks Optional set of task names that were directly resolved (for filtering)
   */
  def buildInvalidationTree(
      taskEdges: Map[String, Seq[String]],
      interestingTasks: Set[String],
      codeSignatureTree: Option[String] = None,
      taskMethodSignatures: Map[String, Set[String]] = Map.empty,
      millVersionChanged: Option[(String, String)] = None,
      millJvmVersionChanged: Option[(String, String)] = None,
      invalidationReasons: Map[String, String] = Map.empty,
      resolvedTasks: Option[Set[String]] = None
  ): ujson.Obj = {
    // Build version change node names
    val versionChangeNodes = Seq(
      millVersionChanged.map { case (oldV, newV) => s"mill-version-changed:$oldV->$newV" },
      millJvmVersionChanged.map { case (oldV, newV) => s"mill-jvm-version-changed:$oldV->$newV" }
    ).flatten

    // Build the graph including version change nodes
    // Version change nodes have edges to all interesting tasks (they invalidate everything)
    val versionChangeEdges: Map[String, Seq[String]] =
      if (versionChangeNodes.nonEmpty)
        versionChangeNodes.map(node => node -> interestingTasks.toSeq).toMap
      else Map.empty

    val allEdges = taskEdges ++ versionChangeEdges
    val allTasks = (allEdges.keys ++ allEdges.values.flatten ++ interestingTasks).toArray.distinct.sorted
    val taskToIndex = allTasks.zipWithIndex.toMap
    val indexEdges = allTasks.map(t => allEdges.getOrElse(t, Nil).flatMap(taskToIndex.get).toArray)

    // Include version change nodes as interesting vertices so they appear in the tree
    val allInteresting = interestingTasks ++ versionChangeNodes
    val interestingIndices = allInteresting.flatMap(taskToIndex.get)

    val baseForest = SpanningForest(indexEdges, interestingIndices, limitToImportantVertices = true)
    val baseTree = spanningTreeToJsonTree(baseForest, allTasks(_))

    // Simplify to only show paths to resolved tasks if specified
    val simplifiedTree = resolvedTasks match {
      case Some(resolved) => simplifyToResolved(baseTree, resolved)
      case None => baseTree
    }

    // Parse code signature tree if provided
    val parsedCodeSigTree: Option[ujson.Obj] = codeSignatureTree.flatMap { jsonStr =>
      try {
        ujson.read(jsonStr) match {
          case obj: ujson.Obj if obj.value.nonEmpty => Some(obj)
          case _ => None
        }
      } catch {
        case _: Exception => None
      }
    }

    // If version changes are present, the tree is already structured correctly
    // (version change nodes are roots with tasks as children)
    if (versionChangeNodes.nonEmpty) {
      return simplifiedTree
    }

    // Group tasks by invalidation reason
    if (invalidationReasons.nonEmpty) {
      val tasksByReason = invalidationReasons.groupMap(_._2)(_._1)
      val result = ujson.Obj()

      for ((reason, tasks) <- tasksByReason.toSeq.sortBy(_._1)) {
        val taskSet = tasks.toSet
        // For code changes, use the full simplified tree (preserves downstream deps)
        // For other reasons, filter to just root causes
        val treeForReason =
          if (reason == "<code changed>") simplifiedTree
          else filterTreeToTasks(simplifiedTree, taskSet)

        if (reason == "<code changed>" && parsedCodeSigTree.isDefined) {
          // Merge code signature tree for code changes
          val merged = mergeCodeSignatureTree(treeForReason, parsedCodeSigTree.get, taskMethodSignatures)
          // Add merged entries directly to result (not under a reason key)
          merged.value.foreach { case (k, v) => result(k) = v }
        } else {
          result(reason) = treeForReason
        }
      }
      result
    } else {
      // No reason grouping - just merge with code signature tree
      parsedCodeSigTree match {
        case Some(codeSigTree) => mergeCodeSignatureTree(simplifiedTree, codeSigTree, taskMethodSignatures)
        case None => simplifiedTree
      }
    }
  }

  /**
   * Simplifies a tree to only show paths that lead to resolved tasks.
   */
  private def simplifyToResolved(tree: ujson.Obj, resolvedTasks: Set[String]): ujson.Obj = {
    def simplify(j: ujson.Obj): Option[ujson.Obj] = {
      val filtered = j.value.flatMap {
        case (k, v: ujson.Obj) =>
          simplify(v)
            .map((k, _))
            .orElse(Option.when(resolvedTasks.contains(k))(k -> v))
        case other => Some(other)
      }
      Option.when(filtered.nonEmpty)(ujson.Obj.from(filtered))
    }
    simplify(tree).getOrElse(ujson.Obj())
  }

  /**
   * Filters a tree to only include paths that start from or lead to specified tasks.
   */
  private def filterTreeToTasks(tree: ujson.Obj, tasks: Set[String]): ujson.Obj = {
    def filter(j: ujson.Obj): ujson.Obj = {
      ujson.Obj.from(j.value.flatMap {
        case (k, v: ujson.Obj) =>
          val filtered = filter(v)
          if (tasks.contains(k) || filtered.value.nonEmpty) Some(k -> filtered)
          else None
        case other => Some(other)
      })
    }
    filter(tree)
  }

  /**
   * Merges the code signature spanning tree with the task invalidation tree.
   * For each root task with a code path, wraps it (and its children) with the code path.
   * Preserves task dependency structure (nested tasks remain nested).
   *
   * @param taskMethodSignatures Map from task name to its method signature prefixes.
   *                             Used to match tasks to their code paths in the spanning tree.
   */
  def mergeCodeSignatureTree(
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
          // Task has a code path - wrap it with the path, preserving its children
          tasksByPath.getOrElseUpdate(path, collection.mutable.Buffer()) += ((taskName, subtree))
        case _ =>
          tasksWithoutPath += ((taskName, subtree))
      }
    }

    // For each unique code path, create a single entry with all tasks underneath
    for ((path, tasks) <- tasksByPath) {
      // Combine all tasks into a single object (preserving their subtrees)
      val combinedTasks = ujson.Obj()
      for ((taskName, subtree) <- tasks) {
        combinedTasks(taskName) = subtree
      }

      // Wrap the combined tasks with the code path
      val wrappedTree = wrapWithPath(path, combinedTasks)

      // Merge into result
      wrappedTree match {
        case obj: ujson.Obj =>
          for ((k, v) <- obj.value) {
            result.value.get(k) match {
              case Some(existing: ujson.Obj) =>
                v match {
                  case vObj: ujson.Obj => deepMerge(existing, vObj)
                  case _ => result(k) = v
                }
              case _ => result(k) = v
            }
          }
        case _ => // shouldn't happen
      }
    }

    // Add tasks without code path directly
    for ((taskName, subtree) <- tasksWithoutPath) {
      result(taskName) = subtree
    }

    result
  }

  /**
   * Finds the path of def/call nodes in the spanning tree that leads to a
   * method signature matching the given task name.
   *
   * @param taskMethodSignatures Map from task name to its method signature prefixes.
   *                             These signatures are computed via CodeSigUtils and contain
   *                             the class name where the task is defined (e.g., "build_.package_$foo$#compile()").
   *
   * We find the deepest matching method in the tree - either a direct match for the task method,
   * or a method that references the task's enclosing class (for module-level changes).
   */
  def findPathForTask(
      taskName: String,
      tree: ujson.Value,
      taskMethodSignatures: Map[String, Set[String]]
  ): Option[Seq[String]] = {
    // Get the method signatures for this task (computed via CodeSigUtils)
    // These have format like "build_.package_$foo$#compile()"
    val signatures = taskMethodSignatures.getOrElse(taskName, Set.empty)

    // Extract class names from signatures (the part before #)
    val classNames = signatures.flatMap { sig =>
      sig.split('#').headOption
    }

    def matchesTask(key: String): Boolean = {
      // Only match "def" nodes (method definitions), not "call" nodes
      if (!key.startsWith("def ")) return false

      val signature = key.stripPrefix("def ")

      // Check for direct task method match (e.g., "build_.package_$foo$#compile()...")
      val directMatch = signatures.exists(sig => signature.startsWith(sig))

      // Check for class-level match (e.g., method returns or references the task's class)
      // This handles cases where the module constructor changed but not the task method
      val classMatch = classNames.exists { cls =>
        // Method returns this class (e.g., "build_.package_#foo()build_.package_$foo$")
        signature.endsWith(cls) ||
        // Method is defined in this class (e.g., "build_.package_$foo$#<init>...")
        signature.startsWith(cls + "#")
      }

      directMatch || classMatch
    }

    // Find the deepest matching method in the tree
    def findDeepest(node: ujson.Value, currentPath: Seq[String]): Option[Seq[String]] = node match {
      case obj: ujson.Obj =>
        var deepestMatch: Option[Seq[String]] = None

        for ((key, value) <- obj.value) {
          val newPath = currentPath :+ key

          // Check if this key matches the task
          if (matchesTask(key)) {
            deepestMatch = Some(newPath)
          }

          // Recursively search children for deeper matches
          findDeepest(value, newPath) match {
            case Some(deeper) => deepestMatch = Some(deeper)
            case None => // keep current match
          }
        }

        deepestMatch
      case _ => None
    }

    findDeepest(tree, Seq.empty)
  }

  /**
   * Wraps content with a def/call chain path.
   */
  def wrapWithPath(path: Seq[String], content: ujson.Value): ujson.Value = {
    if (path.isEmpty) {
      content
    } else {
      // Build from inside out - the content goes at the innermost level
      var current: ujson.Value = content
      for (key <- path.reverse) {
        current = ujson.Obj(key -> current)
      }
      current
    }
  }

  /**
   * Deep merges two ujson.Obj trees.
   */
  def deepMerge(target: ujson.Obj, source: ujson.Obj): Unit = {
    for ((k, v) <- source.value) {
      target.value.get(k) match {
        case Some(existing: ujson.Obj) =>
          v match {
            case vObj: ujson.Obj => deepMerge(existing, vObj)
            case _ => target(k) = v
          }
        case _ => target(k) = v
      }
    }
  }
}
