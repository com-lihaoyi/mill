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
    val (methodEdges, codeRoots, allMethodNodes) = codeSignatureTree match {
      case Some(json) =>
        val tree = ujson.read(json).obj
        extractMethodEdges(tree)
      case None => (Map.empty[String, Seq[String]], Set.empty[String], Set.empty[String])
    }

    // Build method -> task edges
    // 1. Match task method signatures to method nodes
    // 2. Connect leaf methods (no children) to all root invalidated tasks
    val methodToTaskEdges: Map[String, Seq[String]] = if (codeSignatureTree.isDefined) {
      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

      val rootTaskNames = rootInvalidatedTasks.iterator
        .collect { case t: Task.Named[?] => t.ctx.segments.render }
        .toSeq

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

      // Leaf methods (methods with no outgoing edges) should connect to all root tasks
      val leafMethods = allMethodNodes.filter(m => !methodEdges.contains(m))
      val leafEdges = leafMethods.toSeq.map(m => m -> rootTaskNames)

      (matchedEdges ++ leafEdges.flatMap { case (m, ts) => ts.map(t => m -> t) })
        .groupMap(_._1)(_._2)
    } else Map.empty

    // Combine all edges
    val allEdges: Map[String, Seq[String]] = {
      val combined = collection.mutable.Map[String, Seq[String]]()
      for ((k, vs) <- taskEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
      for ((k, vs) <- methodEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
      for ((k, vs) <- methodToTaskEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
      combined.toMap
    }

    // Find all relevant nodes:
    // 1. All method nodes from the code signature tree (these form the invalidation chain)
    // 2. All task nodes (root invalidated tasks and their downstreams)
    val rootTaskStrings = rootInvalidatedTasks.map(_.toString)
    val relevantNodes = {
      val reachable = collection.mutable.Set.from(rootTaskStrings)
      // Include all method nodes from the code signature tree
      reachable ++= allMethodNodes
      val queue = collection.mutable.Queue.from(rootTaskStrings)

      // Downstream: find task nodes reachable from root tasks (via forward edges)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        for (next <- allEdges.getOrElse(current, Nil) if !reachable.contains(next)) {
          reachable += next
          queue.enqueue(next)
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
    val allNodes = (filteredEdges.keys ++ filteredEdges.values.flatten ++ rootTaskStrings ++ codeRoots.filter(relevantNodes))
      .toArray.distinct.sorted

    // Important vertices: all relevant nodes
    val importantStrings = relevantNodes

    val nodeToIndex = allNodes.zipWithIndex.toMap
    val indexEdges = allNodes.map(n => filteredEdges.getOrElse(n, Nil).flatMap(nodeToIndex.get).toArray)
    val importantIndices = importantStrings.flatMap(nodeToIndex.get)

    val forest = SpanningForest(indexEdges, importantIndices, limitToImportantVertices = true)
    SpanningForest.spanningTreeToJsonTree(forest, allNodes(_))
  }

  /**
   * Extracts method -> method edges from a code signature tree.
   * Returns (edges map, root nodes set, all nodes set).
   */
  private def extractMethodEdges(
      tree: ujson.Obj
  ): (Map[String, Seq[String]], Set[String], Set[String]) = {
    val edges = collection.mutable.Map[String, collection.mutable.Buffer[String]]()
    val roots = collection.mutable.Set[String]()
    val allNodes = collection.mutable.Set[String]()

    def traverse(node: ujson.Obj, parent: Option[String], isRoot: Boolean): Unit = {
      for ((key, value) <- node.value) {
        allNodes += key
        if (isRoot) roots += key
        parent.foreach { p =>
          edges.getOrElseUpdate(p, collection.mutable.Buffer()) += key
        }
        value match {
          case obj: ujson.Obj if obj.value.nonEmpty => traverse(obj, Some(key), isRoot = false)
          case _ =>
        }
      }
    }

    traverse(tree, None, isRoot = true)
    (edges.view.mapValues(_.toSeq).toMap, roots.toSet, allNodes.toSet)
  }
}
