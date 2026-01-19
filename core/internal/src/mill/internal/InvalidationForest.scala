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

    computeVersionChangeNode(previousVersions) match {
      case Some(versionNode) => // if mill/mill-jvm version change, that invalidates everything
        val allTaskStrings = rootInvalidatedTasks
          .collect { case t: Task.Named[?] => t.toString }
          .toSeq
          .sorted

        ujson.Obj(versionNode -> ujson.Obj.from(allTaskStrings.map(_ -> ujson.Obj())))

      case None =>
        val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)
        val filteredTaskDeps = reverseInterGroupDeps.view.filterKeys(rootInvalidatedTasks).toMap
        val taskEdges: Map[String, Seq[String]] = filteredTaskDeps
          .map { case (k, vs) => k.toString -> vs.map(_.toString) }

        // Parse code signature tree and extract method -> method edges and method -> task edges
        // Uses CodeSigUtils.allMethodSignatures for consistent matching with codeSigForTask.
        val (methodEdges, allMethodNodes, methodToTaskEdges) = codeSignatureTree match {
          case Some(json) =>
            val (edges, nodes) = extractMethodEdges(ujson.read(json).obj)

            val (classToTransitiveClasses, allTransitiveClassMethods) =
              CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

            // Map from method signature (with "def " prefix) to tasks affected by that method
            val sigToTasks: Map[String, Seq[String]] = rootInvalidatedTasks.iterator
              .collect { case t: Task.Named[?] => t }
              .flatMap { namedTask =>
                try {
                  CodeSigUtils
                    .allMethodSignatures(namedTask, classToTransitiveClasses, allTransitiveClassMethods)
                    .map(sig => s"def $sig" -> namedTask.ctx.segments.render)
                } catch { case _: mill.api.MillException => Nil }
              }
              .toSeq
              .groupMap(_._1)(_._2)

            // Connect any method node that matches a task signature to that task
            // (not just leaf methods - the method may have "call" children that we filter out later)
            val toTaskEdges = nodes.toSeq
              .flatMap(m => sigToTasks.getOrElse(m, Nil).map(m -> _))
              .groupMap(_._1)(_._2)

            (edges, nodes, toTaskEdges)

          case None =>
            (Map.empty[String, Seq[String]], Set.empty[String], Map.empty[String, Seq[String]])
        }

        // Combine all edges: task->task, method->method, and method->task
        // SpanningForest will pick the shortest path when multiple paths exist
        val allEdges: Map[String, Seq[String]] = {
          val combined = collection.mutable.Map[String, Seq[String]]()
          for ((k, vs) <- taskEdges) combined(k) = combined.getOrElse(k, Nil) ++ vs
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
        val reverseAllEdges = SpanningForest.reverseEdges(allEdges)

        // Find all relevant nodes:
        // 1. Forward BFS from root tasks to find downstream tasks
        // 2. Backward BFS from tasks to find method nodes that lead to them
        val forwardReachable = SpanningForest
          .breadthFirst(rootTaskStrings ++ methodToTaskEdges.values.flatten)(n =>
            allEdges.getOrElse(n, Nil)
          )

        val taskNodes = forwardReachable.filterNot(allMethodNodes.contains)
        val relevantNodes = SpanningForest
          .breadthFirst(taskNodes)(n => reverseAllEdges.getOrElse(n, Nil))
          .toSet

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
