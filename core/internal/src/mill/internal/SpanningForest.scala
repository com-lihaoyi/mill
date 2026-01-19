package mill.internal

import mill.api.{BuildInfo, MillException, Segment, Task}

import scala.collection.mutable
import scala.reflect.NameTransformer.encode
import java.lang.reflect.Method

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
   * Helper to compute the method class and encoded task name for a named task.
   * Returns (methodClass, encodedTaskName) or throws MillException if not found.
   */
  def computeMethodClassAndName(
      namedTask: Task.Named[?],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, Method]]
  ): (String, String) = {
    val superTaskName = namedTask.ctx.segments.value.collectFirst {
      case Segment.Label(s"$v.super") => v
    }

    val encodedTaskName = superTaskName match {
      case Some(v) => v
      case None => encode(namedTask.ctx.segments.last.pathSegments.head)
    }

    // For .super tasks (e.g., qux.quxCommand.super.QuxModule), we need to look up
    // the signature for the super class (QuxModule), not the subclass (qux$).
    // The super class name is in the last segment.
    val superClassName = superTaskName.map(_ => namedTask.ctx.segments.last.pathSegments.head)

    def classNameMatches(cls: Class[?], simpleName: String): Boolean = {
      val clsName = cls.getName
      // Match either "package$ClassName" (for nested classes) or "package.ClassName" (for top-level)
      clsName.endsWith("$" + simpleName) || clsName.endsWith("." + simpleName)
    }

    val methodOpt = for {
      parentCls <- classToTransitiveClasses(namedTask.ctx.enclosingCls).iterator
      // For .super tasks, only consider the class that matches the super class name
      if superClassName.forall(scn => classNameMatches(parentCls, scn))
      m <- allTransitiveClassMethods(parentCls).get(encodedTaskName)
    } yield m

    val methodClass = methodOpt
      .nextOption()
      .getOrElse(throw new MillException(
        s"Could not detect the parent class of task ${namedTask}. " +
          s"Please report this at ${BuildInfo.millReportNewIssueUrl} . "
      ))
      .getDeclaringClass.getName

    (methodClass, encodedTaskName)
  }

  /**
   * Computes the method signature prefixes for tasks.
   * Returns a map from task name (e.g., "foo.compile") to a set of method signature prefixes
   * that should match entries in the code signature spanning tree.
   *
   * These prefixes are used by findPathForTask to match task names to their
   * corresponding method signatures in the invalidation tree.
   *
   * Note: We only return task method prefixes, not constructor prefixes. The spanning tree
   * already contains the full path from constructors to task methods, so when we match
   * the task method, we get the complete path including any constructor changes.
   */
  def methodSignaturePrefixesForTasks(
      transitiveNamed: Seq[Task.Named[?]],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, Method]]
  ): Map[String, Set[String]] = {
    transitiveNamed.collect { case namedTask: Task.Named[?] =>
      val taskName = namedTask.ctx.segments.render
      try {
        val (methodClass, encodedTaskName) =
          computeMethodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)

        // The task method signature prefix (matches Task$Simple or Task$Command return types)
        val taskMethodPrefix = methodClass + "#" + encodedTaskName + "()"

        taskName -> Set(taskMethodPrefix)
      } catch {
        case _: MillException => taskName -> Set.empty[String]
      }
    }.toMap
  }

  /**
   * Builds an invalidation tree that combines:
   * - Task dependency spanning forest
   * - Code signature spanning tree (showing method call chains)
   * - Version change nodes (mill-version-changed, mill-jvm-version-changed)
   *
   * @param taskEdges Map from task name to downstream task names
   * @param interestingTasks All tasks that should appear in the tree
   * @param transitiveNamed Tasks for computing method signatures
   * @param classToTransitiveClasses Map from class to its transitive parent classes
   * @param allTransitiveClassMethods Map from class to its declared methods
   * @param codeSignatureTree Optional code signature spanning tree (JSON string to avoid classloader issues)
   * @param millVersionChanged Optional (oldVersion, newVersion) if mill version changed
   * @param millJvmVersionChanged Optional (oldVersion, newVersion) if mill JVM version changed
   * @param resolvedTasks Optional set of task names that were directly resolved (for filtering)
   */
  def buildInvalidationTree(
      taskEdges: Map[String, Seq[String]],
      interestingTasks: Set[String],
      transitiveNamed: Seq[Task.Named[?]],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, Method]],
      codeSignatureTree: Option[String] = None,
      millVersionChanged: Option[(String, String)] = None,
      millJvmVersionChanged: Option[(String, String)] = None,
      resolvedTasks: Option[Set[String]] = None
  ): ujson.Obj = {
    // Compute method signature prefixes for tasks
    val taskMethodSignatures = methodSignaturePrefixesForTasks(
      transitiveNamed,
      classToTransitiveClasses,
      allTransitiveClassMethods
    )
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

    // Merge with code signature tree if available
    parsedCodeSigTree match {
      case Some(codeSigTree) => mergeCodeSignatureTree(simplifiedTree, codeSigTree, taskMethodSignatures)
      case None => simplifiedTree
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
