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
  def computeVersionChangeNode(previousVersions: Option[VersionState]): Option[String] = {
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
      upstreamTaskEdges0: Map[Task[?], Seq[Task[?]]],
      rootInvalidatedTasks: Set[Task[?]],
      codeSignatureTree: Option[String],
      previousVersions: Option[VersionState]
  ): ujson.Obj = {
    val rootInvalidatedTaskStrings = rootInvalidatedTasks
      .collect { case t: Task.Named[?] => t.toString }
      .toSeq
      .sorted

    computeVersionChangeNode(previousVersions) match {
      case Some(versionNode) => // if mill/mill-jvm version change, that invalidates everything
        ujson.Obj(versionNode -> ujson.Obj.from(rootInvalidatedTaskStrings.map(_ -> ujson.Obj())))

      case None =>
        val downstreamTaskEdges0 = SpanningForest.reverseEdges(upstreamTaskEdges0)

        // Code edges: method->method and method->task from code signature tree
        val downstreamCodeEdges = extractCodeEdges(
          codeSignatureTree,
          upstreamTaskEdges0.keys.collect { case t: Task.Named[?] => t }.toSeq,
          rootInvalidatedTasks
        )

        val downstreamTaskEdges: Map[String, Seq[String]] = downstreamTaskEdges0
          .map { case (k, vs) => k.toString -> vs.map(_.toString) }

        val downstreamAllEdges = combineEdges(downstreamTaskEdges, downstreamCodeEdges)

        val allNodes = {
          // For rendering the invalidation tree, we include task nodes downstream of the
          // `rootInvalidatedTaskStrings``, and method nodes upstream of `rootInvalidatedTaskStrings`,
          // but ignore the others since other nodes would not be related to this invalidation
          val allTaskNodes =
            SpanningForest.breadthFirst(rootInvalidatedTaskStrings)(downstreamTaskEdges.getOrElse(
              _,
              Nil
            ))
          val upstreamCodeEdges = SpanningForest.reverseEdges(downstreamCodeEdges)
          val relevantCodeNodes =
            SpanningForest.breadthFirst(rootInvalidatedTaskStrings)(upstreamCodeEdges.getOrElse(
              _,
              Nil
            ))
          (relevantCodeNodes ++ allTaskNodes).toArray.distinct.sorted
        }

        val nodeToIndex = allNodes.zipWithIndex.toMap

        val forest = SpanningForest.applyInferRoots(
          indexGraphEdges = allNodes.map(n =>
            downstreamAllEdges.getOrElse(n, Nil).flatMap(nodeToIndex.get).toArray
          ),
          importantVertices = allNodes.indices.toSet
        )

        SpanningForest.spanningTreeToJsonTree(forest, allNodes(_))
    }
  }

  /**
   * Extracts method->method and method->task edges from a code signature tree.
   * Uses CodeSigUtils.allMethodSignatures for consistent matching with codeSigForTask.
   */
  def extractCodeEdges(
      codeSignatureTree: Option[String],
      transitiveNamed: Seq[Task.Named[?]],
      rootInvalidatedTasks: Set[Task[?]]
  ): Map[String, Seq[String]] = codeSignatureTree match {
    case None => Map.empty
    case Some(json) =>
      val (methodEdges, nodes) = extractMethodEdges(ujson.read(json).obj)

      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

      // Build constructorHashSignatures from tree nodes (signatures have "def " prefix)
      // We use 0 for hash since we only need the signatures for matching, not the hashes
      val constructorHashSignatures: Map[String, Seq[(String, Int)]] = nodes.toSeq
        .collect { case sig @ s"def $prefix#<init>($_)void" => (prefix, sig.stripPrefix("def "), 0) }
        .groupMap(_._1)(t => (t._2, t._3))

      // Map from method signature (with "def " prefix) to tasks affected by that method
      val sigToTasks0 = rootInvalidatedTasks.iterator
        .collect { case t: Task.Named[?] => t }
        .flatMap { namedTask =>
          try {
            val methods = CodeSigUtils
              .allMethodSignatures(
                namedTask,
                classToTransitiveClasses,
                allTransitiveClassMethods,
                constructorHashSignatures
              )

            methods.map(sig => s"def $sig" -> namedTask.ctx.segments.render)
          } catch { case _: mill.api.MillException => Nil }
        }
        .toSeq

      val sigToTasks = sigToTasks0.groupMap(_._1)(_._2)
      // Connect any method node that matches a task signature to that task
      val methodToTaskEdges = nodes.toSeq
        .flatMap(m => sigToTasks.getOrElse(m, Nil).map(m -> _))
        .groupMap(_._1)(_._2)

      combineEdges(methodEdges, methodToTaskEdges)
  }

  def combineEdges(maps: Map[String, Seq[String]]*): Map[String, Seq[String]] = {
    val combined = collection.mutable.Map[String, Seq[String]]()
    for (m <- maps; (k, vs) <- m) combined(k) = combined.getOrElse(k, Nil) ++ vs
    combined.toMap
  }

  /**
   * Extracts method -> method edges from a code signature tree.
   * Returns (edges map, all nodes set).
   */
  def extractMethodEdges(tree: ujson.Obj): (Map[String, Seq[String]], Set[String]) = {
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
