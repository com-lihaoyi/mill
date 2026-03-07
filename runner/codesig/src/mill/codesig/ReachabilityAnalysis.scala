package mill.codesig

import mill.codesig.JvmModel.*
import mill.internal.{SpanningForest, Tarjans}
import ujson.Obj
import upickle.{Writer, writer}

import scala.collection.immutable.SortedMap

class CallGraphAnalysis(
    localSummary: LocalSummary,
    resolved: ResolvedCalls,
    externalSummary: ExternalSummary,
    ignoreCall: (Option[MethodDef], MethodSig) => Boolean,
    logger: Logger,
    prevTransitiveCallGraphHashesOpt: () => Option[Map[String, Int]],
    prevMethodCodeHashesOpt: () => Option[Map[String, Int]]
)(using st: SymbolTable) {

  val methods: Map[MethodDef, LocalSummary.MethodInfo] = for {
    (k, v) <- localSummary.items
    (sig, m) <- v.methods
  } yield (st.MethodDef(k, sig), m)

  // Cache ancestor BFS results at class level so it can be used for both
  // ExternalClsCall pair computation and indexGraphEdges resolution.
  val ancestorCache = new java.util.HashMap[JType.Cls, Set[JType.Cls]]()
  def getTransitiveAncestors(cls: JType.Cls): Set[JType.Cls] = {
    var result = ancestorCache.get(cls)
    if (result == null) {
      result = SpanningForest.breadthFirst(Seq(cls)) { c =>
        if (localSummary.contains(c)) localSummary.items(c).directAncestors
        else externalSummary.directAncestors.getOrElse(c, Nil)
      }.toSet
      ancestorCache.put(cls, result)
    }
    result
  }

  // For each externalDest, find any precise type (receiver or arg) that is a subtype.
  // Returns the narrowing type for that dest, or the dest itself if no precise subtype.
  def narrowForDest(dest: JType.Cls, preciseTypes: Iterable[JType.Cls]): Iterable[JType.Cls] = {
    val subtypes = preciseTypes.filter(pt => getTransitiveAncestors(pt).contains(dest))
    if (subtypes.nonEmpty) subtypes else Iterable(dest)
  }

  // Collect all (dest, narrow) pairs needed as ExternalClsCall proxy nodes, then
  // transitively expand to include (ancestor, narrow) pairs so that each node can
  // chain to its parent. This avoids duplicating callback method edges: each node
  // only handles methods declared at its own level, reaching ancestor methods
  // transitively through ExternalClsCall → ExternalClsCall parent edges.
  val externalClsCallPairs: Set[(JType.Cls, JType.Cls)] = {
    val pairs = collection.mutable.Set.empty[(JType.Cls, JType.Cls)]
    for ((_, methodInfo) <- methods) {
      for (call <- methodInfo.calls) {
        val callInfo = resolved.localCalls(call)
        if (callInfo.externalDests.nonEmpty) {
          val preciseTypes =
            methodInfo.callSiteReceiverType.get(call).toSeq ++
              methodInfo.callSiteArgTypes.getOrElse(call, Set.empty)
          for (dest <- callInfo.externalDests) {
            for (narrow <- narrowForDest(dest, preciseTypes)) pairs += ((dest, narrow))
          }
        }
      }
    }
    // Expand: for each (dest, narrow), add (ancestor, narrow) for all ancestors of dest
    // so that parent chain nodes exist in the graph
    val expanded = collection.mutable.Set.empty[(JType.Cls, JType.Cls)]
    for ((dest, narrow) <- pairs) {
      for (ancestor <- getTransitiveAncestors(dest)) {
        if (externalSummary.directAncestors.contains(ancestor)) {
          expanded += ((ancestor, narrow))
        }
      }
    }
    expanded.toSet
  }

  val indexToNodes: Array[CallGraphAnalysis.Node] =
    methods.keys.toArray.map[CallGraphAnalysis.Node](CallGraphAnalysis.LocalDef(_)) ++
      resolved.localCalls.keys.map(CallGraphAnalysis.Call(_)) ++
      externalClsCallPairs.map(CallGraphAnalysis.ExternalClsCall(_, _))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  def singleAbstractMethods(methodDefCls: JType.Cls) = {
    resolved.classSingleAbstractMethods.getOrElse(methodDefCls, Set.empty)
  }

  // Precompute reverse ancestor index: for each type t, which local classes
  // are subtypes of t? This allows O(1) lookup instead of iterating all
  // localSubs and checking ancestors for each.
  val typeToLocalSubtypes = {
    val result = new java.util.HashMap[JType.Cls, collection.mutable.Set[JType.Cls]]()
    for (localCls <- localSummary.items.keys) {
      for (ancestor <- getTransitiveAncestors(localCls)) {
        var subs = result.get(ancestor)
        if (subs == null) { subs = collection.mutable.Set.empty; result.put(ancestor, subs) }
        subs += localCls
      }
    }
    result
  }

  val indexGraphEdges: Array[Array[Int]] = indexToNodes
    .iterator
    .map {
      case CallGraphAnalysis.Call(methodCall) =>
        // Call nodes resolve to local dests only; external callback edges
        // are handled by ExternalClsCall proxy nodes connected from LocalDef
        resolved.localCalls(methodCall)
          .localDests
          .toArray
          .filter(methodDef => !singleAbstractMethods(methodDef.cls).contains(methodDef.sig))
          .map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))

      case CallGraphAnalysis.LocalDef(methodDef) =>
        val methodInfo = methods(methodDef)
        val calls = methodInfo.calls.toArray
          .filter(c => !ignoreCall(Some(methodDef), c.toMethodSig))

        val edges = Array.newBuilder[Int]

        for (call <- calls) {
          // Always add the Call edge for local dispatch
          edges += nodeToIndex(CallGraphAnalysis.Call(call))

          // For calls with external dests, add ExternalClsCall edges using
          // precise per-call-site types from ASM analysis
          val callInfo = resolved.localCalls(call)
          if (callInfo.externalDests.nonEmpty) {
            val preciseTypes =
              methodInfo.callSiteReceiverType.get(call).toSeq ++
                methodInfo.callSiteArgTypes.getOrElse(call, Set.empty)
            for (dest <- callInfo.externalDests)
              for (narrow <- narrowForDest(dest, preciseTypes))
                nodeToIndex.get(CallGraphAnalysis.ExternalClsCall(dest, narrow)).foreach(edges += _)
          }
        }

        // SAM init edges: when <init> is called, the SAM method is "live"
        if (methodDef.sig.name == "<init>") {
          for {
            samSig <- singleAbstractMethods(methodDef.cls)
            idx <- nodeToIndex.get(CallGraphAnalysis.LocalDef(st.MethodDef(methodDef.cls, samSig)))
          } edges += idx
        }

        edges.result()

      case CallGraphAnalysis.ExternalClsCall(dest, narrow) =>
        // Each node handles only methods declared at this level of the hierarchy.
        // Ancestor methods are reached via parent chain edges to ExternalClsCall(parent, narrow).
        val localMethods = resolved.externalClassLocalDests
          .get(dest).map(_._2).getOrElse(Set.empty)
          .filter(m => !ignoreCall(None, m))
        val localSubs =
          Option(typeToLocalSubtypes.get(narrow)).getOrElse(collection.mutable.Set.empty)

        val edges = Array.newBuilder[Int]
        for (localCls <- localSubs) {
          val sam = singleAbstractMethods(localCls)
          for (m <- localMethods) {
            if (!sam.contains(m)) {
              for (idx <- nodeToIndex.get(CallGraphAnalysis.LocalDef(st.MethodDef(localCls, m))))
                edges += idx
            }
          }
        }
        // Parent chain edges to reach ancestor methods
        for (parent <- externalSummary.directAncestors.getOrElse(dest, Set.empty)) {
          for (idx <- nodeToIndex.get(CallGraphAnalysis.ExternalClsCall(parent, narrow)))
            edges += idx
        }
        edges.result()
    }
    .toArray

  val methodCodeHashes: SortedMap[String, Int] =
    methods.map { case (k, vs) => (k.toString, vs.codeHash) }.to(SortedMap)

  logger.mandatoryLog(methodCodeHashes)

  lazy val prettyCallGraph: SortedMap[String, Array[CallGraphAnalysis.Node]] = {
    indexGraphEdges
      .zip(indexToNodes)
      .map { case (vs, k) => (k.toString, vs.map(indexToNodes)) }
      .to(SortedMap)
  }

  logger.log(prettyCallGraph)

  def transitiveCallGraphValues[V: scala.reflect.ClassTag](
      nodeValues: Array[V],
      reduce: (V, V) => V,
      zero: V
  ): Array[(CallGraphAnalysis.Node, V)] = CallGraphAnalysis.transitiveCallGraphValues[V](
    indexGraphEdges,
    indexToNodes,
    nodeValues,
    reduce,
    zero
  )

  val nodeValues: Array[Int] = indexToNodes.map {
    case CallGraphAnalysis.LocalDef(m) => methods(m).codeHash
    case _ => 0
  }

  val transitiveCallGraphHashes0: Array[(CallGraphAnalysis.Node, Int)] =
    transitiveCallGraphValues[Int](
      nodeValues = nodeValues,
      reduce = _ + _,
      zero = 0
    )
  val transitiveCallGraphHashes: SortedMap[String, Int] = transitiveCallGraphHashes0
    .collect { case (CallGraphAnalysis.LocalDef(d), v) => (d.toString, v) }
    .to(SortedMap)

  logger.mandatoryLog(transitiveCallGraphHashes0)
  logger.log(transitiveCallGraphHashes)

  lazy val spanningInvalidationTree: Obj = prevTransitiveCallGraphHashesOpt() match {
    case Some(prevTransitiveCallGraphHashes) =>
      CallGraphAnalysis.spanningInvalidationTree(
        prevTransitiveCallGraphHashes,
        prevMethodCodeHashesOpt(),
        methodCodeHashes,
        transitiveCallGraphHashes0,
        indexToNodes,
        indexGraphEdges
      )
    case None => ujson.Obj()
  }

  logger.mandatoryLog(spanningInvalidationTree, indent = 2)
}

object CallGraphAnalysis {

  /**
   * Computes the minimal spanning forest of the that covers the nodes in the
   * call graph whose transitive call graph hashes has changed since the last
   * run, rendered as a JSON dictionary tree. This provides a great "debug
   * view" that lets you easily Cmd-F to find a particular node and then trace
   * it up the JSON hierarchy to figure out what upstream node was the root
   * cause of the change in the callgraph.
   *
   * There are typically multiple possible spanning forests for a given graph;
   * one is chosen arbitrarily. This is usually fine, since when debugging you
   * typically are investigating why there's a path to a node at all where none
   * should exist, rather than trying to fully analyse all possible paths
   */
  /**
   * Computes the spanning invalidation tree showing which code changes caused
   * which transitive hash changes.
   *
   * @param prevTransitiveCallGraphHashes Previous transitive hashes (to detect what changed)
   * @param prevMethodCodeHashesOpt Previous method code hashes (to find actual code changes)
   * @param methodCodeHashes Current method code hashes
   * @param transitiveCallGraphHashes0 Current transitive hashes
   * @param indexToNodes Node index to node mapping
   * @param indexGraphEdges Call graph edges (caller -> callees)
   */
  def spanningInvalidationTree(
      prevTransitiveCallGraphHashes: Map[String, Int],
      prevMethodCodeHashesOpt: Option[Map[String, Int]],
      methodCodeHashes: collection.immutable.SortedMap[String, Int],
      transitiveCallGraphHashes0: Array[(CallGraphAnalysis.Node, Int)],
      indexToNodes: Array[Node],
      indexGraphEdges: Array[Array[Int]]
  ): ujson.Obj = {
    val transitiveCallGraphHashes0Map = transitiveCallGraphHashes0.toMap

    // Nodes with changed transitive hashes (these need to be included in the tree)
    val nodesWithChangedTransitiveHashes = indexGraphEdges
      .indices
      .filter { nodeIndex =>
        val currentValue = transitiveCallGraphHashes0Map(indexToNodes(nodeIndex))
        val prevValue = prevTransitiveCallGraphHashes.get(indexToNodes(nodeIndex).toString)
        !prevValue.contains(currentValue)
      }
      .toSet

    if (nodesWithChangedTransitiveHashes.isEmpty) return ujson.Obj()

    // Find nodes whose actual code changed (these are the true root causes)
    // Only LocalDef nodes have code hashes
    val nodesWithChangedCode: Set[Int] = prevMethodCodeHashesOpt match {
      case Some(prevMethodCodeHashes) =>
        indexGraphEdges.indices.filter { nodeIndex =>
          indexToNodes(nodeIndex) match {
            case LocalDef(m) =>
              val key = m.toString
              val currHash = methodCodeHashes.get(key)
              val prevHash = prevMethodCodeHashes.get(key)
              currHash != prevHash
            case _ => false
          }
        }.toSet
      case None =>
        // If no previous hashes, fall back to using transitive hash changes
        nodesWithChangedTransitiveHashes
    }

    // Build reverse graph using two-pass approach to avoid allocating millions of tuples
    val inDegree = new Array[Int](indexGraphEdges.length)
    for (vs <- indexGraphEdges; v <- vs) inDegree(v) += 1

    val reverseGraphEdges = inDegree.map(new Array[Int](_))
    val fillIndex = new Array[Int](indexGraphEdges.length)
    var srcIdx = 0
    while (srcIdx < indexGraphEdges.length) {
      val dests = indexGraphEdges(srcIdx)
      var j = 0
      while (j < dests.length) {
        val dest = dests(j)
        reverseGraphEdges(dest)(fillIndex(dest)) = srcIdx
        fillIndex(dest) += 1
        j += 1
      }
      srcIdx += 1
    }

    // Use actual code changes as roots, but include all transitively affected nodes
    SpanningForest.spanningTreeToJsonTree(
      SpanningForest.applyWithRoots(
        reverseGraphEdges,
        rootsOrdered = nodesWithChangedCode.toSeq.sorted,
        importantVertices = nodesWithChangedTransitiveHashes
      ),
      k => indexToNodes(k).toString
    )
  }

  /**
   * Summarizes the transitive closure of the given graph, using the given
   * [[computeOutputValue]] and [[reduce]] functions to return a single value of [[T]].
   *
   * This is done in topological order, in order to allow us to memo-ize the
   * values computed for upstream groups when processing downstream methods,
   * avoiding the need to repeatedly re-compute them. Each Strongly Connected
   * Component is processed together and assigned the same final value, since
   * they all have the exact same transitive closure
   */
  def transitiveCallGraphValues[V: scala.reflect.ClassTag](
      indexGraphEdges: Array[Array[Int]],
      indexToNodes: Array[Node],
      nodeValues: Array[V],
      reduce: (V, V) => V,
      zero: V
  ): Array[(Node, V)] = {
    val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges)

    // Use array for O(1) group lookups instead of HashMap
    val nodeGroupsArray = new Array[Int](indexToNodes.length)
    for (groupIndex <- topoSortedMethodGroups.indices)
      for (node <- topoSortedMethodGroups(groupIndex))
        nodeGroupsArray(node) = groupIndex

    val seenGroupValues = new Array[V](topoSortedMethodGroups.length)
    val seenUpstreamGroups = new java.util.BitSet(topoSortedMethodGroups.length)
    for (groupIndex <- topoSortedMethodGroups.indices) {
      seenUpstreamGroups.clear()
      var value: V = zero
      for (node <- topoSortedMethodGroups(groupIndex)) {
        value = reduce(value, nodeValues(node))
        for (upstreamNode <- indexGraphEdges(node)) {
          val upstreamGroup = nodeGroupsArray(upstreamNode)
          if (upstreamGroup != groupIndex && !seenUpstreamGroups.get(upstreamGroup)) {
            seenUpstreamGroups.set(upstreamGroup)
            value = reduce(value, seenGroupValues(upstreamGroup))
          }
        }
      }
      seenGroupValues(groupIndex) = value
    }

    seenGroupValues
      .zipWithIndex
      .flatMap { case (groupHash, groupIndex) =>
        topoSortedMethodGroups(groupIndex).map { nodeIndex =>
          (indexToNodes(nodeIndex), groupHash)
        }
      }
  }

  /**
   * Represents the three types of nodes in our call graph:
   * - LocalDef: a method definition in local code, with a code hash
   * - Call: a method call site, connecting to its resolved local destinations
   * - ExternalClsCall(dest, narrow): a shared proxy node for external callback
   *   edges. `dest` determines which callback methods are possible (by walking
   *   dest's ancestors in externalClassLocalDests). `narrow` determines which
   *   local subtypes receive those callbacks (subtypes of narrow). When a
   *   precise bytecode type (receiver or arg) is a subtype of dest, it serves
   *   as narrow for more precise fan-out; otherwise dest == narrow (no narrowing).
   */
  sealed trait Node

  implicit def nodeRw: Writer[Node] = upickle.stringKeyW(
    writer[String].comap[Node](_.toString)
  )

  case class LocalDef(call: MethodDef) extends Node {
    override def toString: String = "def " + call.toString
  }
  case class Call(call: MethodCall) extends Node {
    override def toString: String = "call " + call.toString
  }
  case class ExternalClsCall(dest: JType.Cls, narrow: JType.Cls) extends Node {
    override def toString: String =
      if (dest == narrow) "external " + dest.toString
      else s"external ${dest.toString} narrowed ${narrow.toString}"
  }
}
