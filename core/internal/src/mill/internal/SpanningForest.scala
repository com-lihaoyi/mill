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
   * Merges the code signature spanning tree with the task invalidation tree.
   * Groups tasks by their code path and wraps each group together to preserve
   * the task dependency hierarchy.
   *
   * Only merges if the base tree represents code-change invalidation (flat structure
   * where all tasks are at root level with empty children). Nested structure indicates
   * source/input changes where the spanning tree is irrelevant.
   */
  def mergeCodeSignatureTree(baseTree: ujson.Obj, spanningTree: ujson.Value): ujson.Obj = {
    // Check if base tree has nested structure (non-code-change invalidation)
    // If any root task has non-empty children, it means tasks were invalidated via
    // dependency chains (source/input changes), not by code changes
    val hasNestedStructure = baseTree.value.exists { case (_, subtree) =>
      subtree match {
        case obj: ujson.Obj => obj.value.nonEmpty
        case _ => false
      }
    }

    if (hasNestedStructure) {
      // Don't merge - this is a source/input change, not a code change
      return baseTree
    }

    val result = ujson.Obj()

    // Group tasks by their code path
    val tasksByPath = collection.mutable.Map[Seq[String], collection.mutable.Buffer[(String, ujson.Value)]]()
    val tasksWithoutPath = collection.mutable.Buffer[(String, ujson.Value)]()

    for ((taskName, subtree) <- baseTree.value) {
      findPathForTask(taskName, spanningTree) match {
        case Some(path) if path.nonEmpty =>
          tasksByPath.getOrElseUpdate(path, collection.mutable.Buffer()) += ((taskName, subtree))
        case _ =>
          tasksWithoutPath += ((taskName, subtree))
      }
    }

    // For each unique code path, create a single entry with all tasks underneath
    for ((path, tasks) <- tasksByPath) {
      // Combine all tasks into a single object
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
   * Task names like "foo.compile" can match:
   * 1. Task method signatures like "def build_.package_$foo$#compile()..."
   * 2. Module method signatures like "def build_.package_#foo()..." when the
   *    module constructor was changed
   *
   * We find the deepest matching method in the tree.
   */
  def findPathForTask(taskName: String, tree: ujson.Value): Option[Seq[String]] = {
    val parts = taskName.split('.')
    if (parts.isEmpty) return None

    val taskMethod = parts.last
    val moduleParts = parts.init

    // Build patterns to match method signatures
    // For "foo.compile", we look for:
    // 1. #compile( in a class containing $foo$
    // 2. #foo() for the module definition itself
    val lastModuleName = if (moduleParts.nonEmpty) moduleParts.last else taskMethod

    def matchesTask(key: String): Boolean = {
      if (!key.startsWith("def ")) return false

      // Match task method: contains the module class and task method name
      val matchesTaskMethod = key.contains(s"#$taskMethod(") ||
        key.contains(s"#$taskMethod$$")

      // Match module method: ends with #moduleName()
      val matchesModuleMethod = key.contains(s"#$lastModuleName()")

      // Also check for the module class pattern
      val moduleClassPattern = moduleParts.map(p => s"$$${p}$$").mkString
      val containsModuleClass = moduleClassPattern.isEmpty || key.contains(moduleClassPattern)

      (matchesTaskMethod || matchesModuleMethod) && containsModuleClass
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
