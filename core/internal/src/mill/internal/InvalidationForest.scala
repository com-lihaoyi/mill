package mill.internal

import mill.api.Task
import mill.api.daemon.VersionState

/**
 * Builds invalidation trees for displaying task invalidation reasons.
 * Used by `mill-invalidation-tree.json` and `selective.resolveTree`.
 */
object InvalidationForest {

  // Regex to extract return type from method signature: "def class#method()ReturnType"
  private val ReturnTypePattern = """\)([^\s]+)$""".r

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
   * Builds an invalidation tree by combining all edge types into a single graph:
   * - task -> task edges (from interGroupDeps)
   * - method -> method edges (from codeSignatureTree)
   * - method -> task edges (linking methods to their tasks)
   * - versionchange -> task edges (when version changes)
   *
   * Then runs SpanningForest on the combined graph to produce the tree.
   */
  def buildInvalidationTree(
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      rootInvalidatedTasks: Set[Task[?]],
      codeSignatureTree: Option[String],
      previousVersions: Option[VersionState]
  ): ujson.Obj = {
    val transitiveNamed = interGroupDeps.keys.collect { case t: Task.Named[?] => t }.toSeq

    // Check for version change - simple case with flat structure
    computeVersionChangeNode(previousVersions) match {
      case Some(versionNode) =>
        val allTaskStrings = rootInvalidatedTasks
          .collect { case t: Task.Named[?] => t.toString }
          .toSeq
          .sorted
        return ujson.Obj(
          versionNode -> ujson.Obj.from(allTaskStrings.map(_ -> ujson.Obj()))
        )
      case None =>
    }

    // Build task -> task edges (reverse direction: source -> dependents)
    val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)
    val filteredTaskDeps = reverseInterGroupDeps.view.filterKeys(rootInvalidatedTasks).toMap
    val taskEdges: Map[String, Seq[String]] = filteredTaskDeps
      .map { case (k, vs) => k.toString -> vs.map(_.toString) }

    // Parse code signature tree and extract method -> method edges
    val (methodEdges, allMethodNodes) = codeSignatureTree match {
      case Some(json) => extractMethodEdges(ujson.read(json).obj)
      case None => (Map.empty[String, Seq[String]], Set.empty[String])
    }

    // Build method -> task edges
    // 1. Match task method signatures to method nodes
    // 2. Connect leaf methods (no children) to all root invalidated tasks
    val methodToTaskEdges: Map[String, Seq[String]] = if (codeSignatureTree.isDefined) {
      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

      // Match methods to tasks by task method signature
      val matchedEdges = rootInvalidatedTasks.iterator
        .collect { case t: Task.Named[?] => t }
        .flatMap { namedTask =>
          val taskName = namedTask.ctx.segments.render
          try {
            val (methodClass, encodedTaskName) = CodeSigUtils
              .methodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)
            val methodSig = s"def $methodClass#$encodedTaskName()"
            // Find matching method node in all method nodes from code signature tree
            val matchingMethod = allMethodNodes.find(m => m.startsWith(methodSig))
            matchingMethod.map(m => m -> taskName)
          } catch {
            case _: mill.api.MillException => None
          }
        }
        .toSeq

      // Leaf methods (methods with no outgoing edges) should connect to tasks in their module
      // Use the enclosing module class to match tasks to methods
      val leafMethods = allMethodNodes.filter(m => !methodEdges.contains(m))

      // Build a map from module class name to tasks in that module
      val moduleClassToTasks: Map[String, Seq[String]] = rootInvalidatedTasks.iterator
        .collect { case t: Task.Named[?] => t }
        .map { namedTask =>
          val moduleClassName = namedTask.ctx.enclosingCls.getName
          (moduleClassName, namedTask.ctx.segments.render)
        }
        .toSeq
        .groupMap(_._1)(_._2)

      // Connect leaf methods to tasks based on return type (module class)
      // Method pattern: "def ...()ModuleClassName" where return type is the module class
      val leafEdges = leafMethods.toSeq.flatMap { method =>
        val returnType = ReturnTypePattern.findFirstMatchIn(method).map(_.group(1))
        val matchingTasks = returnType.flatMap(rt => moduleClassToTasks.get(rt)).getOrElse(Nil)
        matchingTasks.map(t => method -> t)
      }

      (matchedEdges ++ leafEdges).groupMap(_._1)(_._2)
    } else Map.empty

    // Combine all edges
    // When we have code signature information, method->task edges show the root cause.
    // Task->task edges are only used for downstream propagation FROM tasks connected
    // to the method chain. We exclude task edges TO tasks that are already connected
    // via method edges to avoid competing paths.
    val tasksConnectedFromMethods = methodToTaskEdges.values.flatten.toSet
    val allEdges: Map[String, Seq[String]] = {
      val combined = collection.mutable.Map[String, Seq[String]]()
      // Include task edges, but filter out values that are already connected from methods
      for ((k, vs) <- taskEdges) {
        val filtered = vs.filterNot(tasksConnectedFromMethods)
        if (filtered.nonEmpty) combined(k) = combined.getOrElse(k, Nil) ++ filtered
      }
      for ((k, vs) <- methodEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
      for ((k, vs) <- methodToTaskEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
      combined.toMap
    }

    // Find all relevant nodes by working backwards from root invalidated tasks
    // 1. Start with root invalidated tasks
    // 2. Find methods that connect to these tasks (reverse direction)
    // 3. Find downstream tasks via forward traversal
    val rootTaskStrings = rootInvalidatedTasks.map(_.toString)

    // Build reverse edge map for backward traversal
    val reverseAllEdges: Map[String, Seq[String]] = {
      val reversed = collection.mutable.Map[String, collection.mutable.Buffer[String]]()
      for ((from, tos) <- allEdges; to <- tos) {
        reversed.getOrElseUpdate(to, collection.mutable.Buffer()) += from
      }
      reversed.view.mapValues(_.toSeq).toMap
    }

    val relevantNodes = {
      val reachable = collection.mutable.Set.from(rootTaskStrings)
      // Include all task names from method->task edges
      reachable ++= methodToTaskEdges.values.flatten
      val queue = collection.mutable.Queue.from(rootTaskStrings)

      // Forward: find task nodes reachable from root tasks (downstream propagation)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        for (next <- allEdges.getOrElse(current, Nil) if !reachable.contains(next)) {
          reachable += next
          queue.enqueue(next)
        }
      }

      // Backward: find method nodes that lead to relevant tasks
      val taskNodeStrings = reachable.filter(!allMethodNodes.contains(_)).toSeq
      val backwardQueue = collection.mutable.Queue.from(taskNodeStrings)
      while (backwardQueue.nonEmpty) {
        val current = backwardQueue.dequeue()
        for (prev <- reverseAllEdges.getOrElse(current, Nil) if !reachable.contains(prev)) {
          reachable += prev
          backwardQueue.enqueue(prev)
        }
      }

      reachable.toSet
    }

    // Filter to only include relevant nodes and edges
    val filteredEdges = allEdges.view
      .filterKeys(relevantNodes)
      .mapValues(_.filter(relevantNodes))
      .toMap

    // Collect all nodes
    val allNodes = (filteredEdges.keys ++ filteredEdges.values.flatten ++ rootTaskStrings)
      .toArray.distinct.sorted

    val nodeToIndex = allNodes.zipWithIndex.toMap
    val indexEdges = allNodes.map(n => filteredEdges.getOrElse(n, Nil).flatMap(nodeToIndex.get).toArray)
    val importantIndices = relevantNodes.flatMap(nodeToIndex.get)

    val forest = SpanningForest(indexEdges, importantIndices, limitToImportantVertices = true)
    SpanningForest.spanningTreeToJsonTree(forest, allNodes(_))
  }

  /**
   * Extracts method -> method edges from a code signature tree.
   * Returns (edges map, all nodes set).
   */
  private def extractMethodEdges(tree: ujson.Obj): (Map[String, Seq[String]], Set[String]) = {
    val edges = collection.mutable.Map[String, collection.mutable.Buffer[String]]()
    val allNodes = collection.mutable.Set[String]()

    def traverse(node: ujson.Obj, parent: Option[String]): Unit = {
      for ((key, value) <- node.value) {
        allNodes += key
        parent.foreach { p =>
          edges.getOrElseUpdate(p, collection.mutable.Buffer()) += key
        }
        value match {
          case obj: ujson.Obj if obj.value.nonEmpty => traverse(obj, Some(key))
          case _ =>
        }
      }
    }

    traverse(tree, None)
    (edges.view.mapValues(_.toSeq).toMap, allNodes.toSet)
  }
}
