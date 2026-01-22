package mill.internal

import mill.api.Task

/**
 * Builds invalidation trees for displaying task invalidation reasons.
 * Used by `mill-invalidation-tree.json` and `selective.resolveTree`.
 */
object InvalidationForest {

  /**
   * Builds an invalidation tree by splicing together the task-invalidation-graph
   * and method-codesig-invalidation-graph.
   *
   * @param taskInvalidationReasons Per-task invalidation reasons (e.g., "mill-version-changed:OLD->NEW").
   *                                Tasks with the same reason are grouped under that reason node.
   */
  def buildInvalidationTree(
      upstreamTaskEdges0: Map[Task[?], Seq[Task[?]]],
      rootInvalidatedTasks: Set[Task[?]],
      codeSignatureTree: Option[String],
      taskInvalidationReasons: Map[String, String] = Map()
  ): ujson.Obj = {
    // Separate tasks by their invalidation reason
    val rootTaskNames = rootInvalidatedTasks.collect { case t: Task.Named[?] => t.ctx.segments.render }
    val tasksWithReasons = rootTaskNames.filter(taskInvalidationReasons.contains)
    val tasksWithoutReasons = rootTaskNames -- tasksWithReasons

    // Group tasks by reason for display
    val tasksByReason = tasksWithReasons.toSeq
      .groupBy(taskInvalidationReasons)
      .view
      .mapValues(_.sorted)
      .toMap

    // Build the main tree for tasks without special reasons
    val mainTree = if (tasksWithoutReasons.isEmpty && tasksByReason.isEmpty) {
      ujson.Obj()
    } else if (tasksWithoutReasons.isEmpty) {
      ujson.Obj()
    } else {
      val rootInvalidatedTaskStrings = rootInvalidatedTasks
        .collect { case t: Task.Named[?] if tasksWithoutReasons.contains(t.ctx.segments.render) => t.toString }
        .toSeq
        .sorted

      val downstreamTaskEdges0 = SpanningForest.reverseEdges(upstreamTaskEdges0)

      // Code edges: method->method and method->task from code signature tree
      val (methodForest, downstreamMethodEdges) = extractMethodEdges(
        codeSignatureTree,
        upstreamTaskEdges0.keys.collect { case t: Task.Named[?] => t }.toSeq,
        rootInvalidatedTasks.filter {
          case t: Task.Named[?] => tasksWithoutReasons.contains(t.ctx.segments.render)
          case _ => false
        }
      )

      val taskForest = buildTaskForest(rootInvalidatedTaskStrings, downstreamTaskEdges0)

      // Using the `crossEdges` to identify connection points where we splice
      // a top-level tree from `taskForest` into `methodForest`.
      def combineRecursive(node: ujson.Value): Unit = {
        node.obj.valuesIterator.foreach(combineRecursive)
        for (key <- node.obj.keysIterator.toArray) {
          for {
            crossKeys <- downstreamMethodEdges.get(key)
            crossKey <- crossKeys.sorted
            subTaskTree <- taskForest.obj.remove(crossKey)
          } node.obj(key)(crossKey) = subTaskTree

          if (node.obj(key).obj.isEmpty) node.obj.remove(key)
        }
      }

      combineRecursive(methodForest)
      // Any un-spliced top-level trees from taskForest become top-level trees in methodForest
      for ((k, v) <- taskForest.obj) methodForest(k) = v

      methodForest.asInstanceOf[ujson.Obj]
    }

    // Add reason-grouped tasks as top-level nodes
    for ((reason, tasks) <- tasksByReason.toSeq.sortBy(_._1)) {
      val childObj = ujson.Obj()
      for (name <- tasks) childObj(name) = ujson.Obj()
      mainTree(reason) = childObj
    }

    mainTree
  }

  def buildTaskForest(
      rootInvalidatedTaskStrings: collection.Seq[String],
      downstreamTaskEdges0: Map[Task[?], Vector[Task[?]]]
  ) = {
    val downstreamTaskEdges: Map[String, Seq[String]] = downstreamTaskEdges0
      .map { case (k, vs) => k.toString -> vs.map(_.toString) }

    val allTaskNodes = SpanningForest
      .breadthFirst(rootInvalidatedTaskStrings)(downstreamTaskEdges.getOrElse(_, Nil))

    val taskNodeToIndex = allTaskNodes.zipWithIndex.toMap

    val taskForest = SpanningForest.applyInferRoots(
      indexGraphEdges = allTaskNodes
        .map(n => downstreamTaskEdges.getOrElse(n, Nil).flatMap(taskNodeToIndex.get).toArray)
        .toArray,
      importantVertices = allTaskNodes.indices.toSet
    )

    SpanningForest.spanningTreeToJsonTree(taskForest, allTaskNodes(_))
  }

  /**
   * Extracts method->method and method->task edges from a code signature tree.
   * Uses CodeSigUtils.allMethodSignatures for consistent matching with codeSigForTask.
   */
  def extractMethodEdges(
      codeSignatureTree: Option[String],
      transitiveNamed: Seq[Task.Named[?]],
      rootInvalidatedTasks: Set[Task[?]]
  ): (ujson.Value, Map[String, Seq[String]]) = codeSignatureTree match {
    case None => (ujson.Obj(), Map.empty)
    case Some(json) =>
      val jsonTree = ujson.read(json)
      val (methodEdges, nodes) = extractMethodEdges(jsonTree.obj)

      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(transitiveNamed)

      // Build constructorHashSignatures from tree nodes (signatures have "def " prefix)
      // We use 0 for hash since we only need the signatures for matching, not the hashes
      val constructorHashSignatures: Map[String, Seq[(String, Int)]] = nodes.toSeq
        .collect { case sig @ s"def $prefix#<init>($_)void" =>
          (prefix, sig.stripPrefix("def "), 0)
        }
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

      (jsonTree, methodToTaskEdges)
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
