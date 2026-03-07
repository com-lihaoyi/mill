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

  val indexToNodes: Array[CallGraphAnalysis.Node] =
    methods.keys.toArray.map[CallGraphAnalysis.Node](CallGraphAnalysis.LocalDef(_)) ++
      resolved.localCalls.keys.map(CallGraphAnalysis.Call(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges: Array[Array[Int]] = CallGraphAnalysis.indexGraphEdges(
    indexToNodes,
    localSummary,
    methods,
    resolved,
    externalSummary,
    nodeToIndex,
    ignoreCall
  )

  val methodCodeHashes: SortedMap[String, Int] =
    methods.map { case (k, vs) => (k.toString, vs.codeHash) }.to(SortedMap)

  logger.mandatoryLog(methodCodeHashes)

  lazy val prettyCallGraph: SortedMap[String, Array[CallGraphAnalysis.Node]] = {
    indexGraphEdges.zip(indexToNodes).map { case (vs, k) =>
      (k.toString, vs.map(indexToNodes))
    }
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

  def indexGraphEdges(
      indexToNodes: Array[Node],
      localSummary: LocalSummary,
      methods: Map[MethodDef, LocalSummary.MethodInfo],
      resolved: ResolvedCalls,
      externalSummary: ExternalSummary,
      nodeToIndex: Map[CallGraphAnalysis.Node, Int],
      ignoreCall: (Option[MethodDef], MethodSig) => Boolean
  )(using st: SymbolTable): Array[Array[Int]] = {

    def singleAbstractMethods(methodDefCls: JType.Cls) = {
      resolved.classSingleAbstractMethods.getOrElse(methodDefCls, Set.empty)
    }

    // Cache ancestor BFS results using java.util.HashMap for faster lookups
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

    // Precompute reverse ancestor index: for each type t, which local classes
    // are subtypes of t? This allows O(1) lookup instead of iterating all
    // localSubs and checking ancestors for each.
    val typeToLocalSubtypes = {
      val allLocalClasses = collection.mutable.Set.empty[JType.Cls]
      for ((_, (subs, _)) <- resolved.externalClassLocalDests) allLocalClasses ++= subs
      for (cls <- localSummary.items.keys) allLocalClasses += cls

      val result = new java.util.HashMap[JType.Cls, collection.mutable.Set[JType.Cls]]()
      for (localCls <- allLocalClasses) {
        for (ancestor <- getTransitiveAncestors(localCls)) {
          var subs = result.get(ancestor)
          if (subs == null) { subs = collection.mutable.Set.empty; result.put(ancestor, subs) }
          subs += localCls
        }
      }
      result
    }

    // Precompute callback indices per extType. For a given extType, walks its
    // ancestors to collect callback method signatures, then for each localSub
    // resolves the valid node indices (filtering SAM/ignoreCall).
    // Returns (localSubsSet, subToIndices) where localSubsSet is a Set for O(1) contains.
    val callbackIndicesCache =
      new java.util.HashMap[JType.Cls, (Set[JType.Cls], Map[JType.Cls, Array[Int]])]()
    val emptyCallbackInfo: (Set[JType.Cls], Map[JType.Cls, Array[Int]]) =
      (Set.empty, Map.empty)
    def getCallbackInfo(
        extType: JType.Cls
    ): (Set[JType.Cls], Map[JType.Cls, Array[Int]]) = {
      var result = callbackIndicesCache.get(extType)
      if (result == null) {
        val localSubs: Iterable[JType.Cls] =
          resolved.externalClassLocalDests.get(extType).map(_._1).getOrElse(
            if (localSummary.contains(extType)) Seq(extType) else Nil
          )
        if (localSubs.isEmpty) {
          result = emptyCallbackInfo
        } else {
          val allMethods = getTransitiveAncestors(extType).flatMap { ancestorCls =>
            resolved.externalClassLocalDests.get(ancestorCls).map(_._2).getOrElse(Set.empty)
          }.filter(m => !ignoreCall(None, m))

          val subToIndices = localSubs.iterator.map { localCls =>
            val sam = singleAbstractMethods(localCls)
            val indices = allMethods.flatMap { m =>
              if (sam.contains(m)) None
              else nodeToIndex.get(CallGraphAnalysis.LocalDef(st.MethodDef(localCls, m)))
            }.toArray
            localCls -> indices
          }.toMap

          result = (localSubs.toSet, subToIndices)
        }
        callbackIndicesCache.put(extType, result)
      }
      result
    }

    val emptyIntArray = Array.empty[Int]

    indexToNodes
      .iterator
      .map {
        case CallGraphAnalysis.Call(methodCall) =>
          val callInfo = resolved.localCalls(methodCall)
          callInfo
            .localDests
            .toArray
            .filter(methodDef => !singleAbstractMethods(methodDef.cls).contains(methodDef.sig))
            .map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))

        case CallGraphAnalysis.LocalDef(methodDef) =>
          val calls = methods(methodDef)
            .calls
            .toArray
            .filter(c => !ignoreCall(Some(methodDef), c.toMethodSig))

          val normalEdges = Array.newBuilder[Int]
          val callbackEdges = new java.util.BitSet(indexToNodes.length)
          val methodInfo = methods(methodDef)

          // Precompute local subtypes of the caller's class (shared across all calls)
          val callerClassSubs = typeToLocalSubtypes.get(methodDef.cls)

          for (call <- calls) {
            val callInfo = resolved.localCalls(call)
            if (callInfo.externalDests.isEmpty) {
              normalEdges += nodeToIndex(CallGraphAnalysis.Call(call))
            } else {
              // Include local dests directly (same filtering as the Call node case)
              for {
                dest <- callInfo.localDests
                if !singleAbstractMethods(dest.cls).contains(dest.sig)
              } callbackEdges.set(nodeToIndex(CallGraphAnalysis.LocalDef(dest)))

              val argTypes = methodInfo.callSiteArgTypes.getOrElse(call, Set.empty)
              val receiverType = methodInfo.callSiteReceiverType.get(call)

              val allExtTypes =
                if (argTypes.nonEmpty) callInfo.externalDests ++ argTypes
                else callInfo.externalDests

              // Build matching local subs from precise types using reverse index.
              // Optimize common case: no args/receiver → use caller class subs directly
              val matchingLocalSubs: collection.Set[JType.Cls] =
                if (argTypes.isEmpty && receiverType.isEmpty) {
                  if (callerClassSubs != null) callerClassSubs
                  else collection.Set.empty
                } else {
                  val s = collection.mutable.Set.empty[JType.Cls]
                  if (callerClassSubs != null) s ++= callerClassSubs
                  for (rt <- receiverType) {
                    val subs = typeToLocalSubtypes.get(rt)
                    if (subs != null) s ++= subs
                  }
                  for (at <- argTypes) {
                    val subs = typeToLocalSubtypes.get(at)
                    if (subs != null) s ++= subs
                  }
                  s
                }

              for (extType <- allExtTypes) {
                val (localSubsSet, subToIndices) = getCallbackInfo(extType)
                // Iterate from the smaller side to minimize lookups
                val (iterSet, checkSet) =
                  if (matchingLocalSubs.size <= localSubsSet.size)
                    (matchingLocalSubs, localSubsSet)
                  else (localSubsSet, matchingLocalSubs)
                for (s <- iterSet) {
                  if (checkSet.contains(s)) {
                    for (idx <- subToIndices.getOrElse(s, emptyIntArray))
                      callbackEdges.set(idx)
                  }
                }
              }
            }
          }

          val singleAbstractMethodInitEdge =
            if (methodDef.sig.name != "<init>") None
            else {
              singleAbstractMethods(methodDef.cls)
                .flatMap(samSig => nodeToIndex.get(LocalDef(st.MethodDef(methodDef.cls, samSig))))
            }

          // Efficiently assemble result: normal edges + callback BitSet + SAM edges
          val ne = normalEdges.result()
          val ceCard = callbackEdges.cardinality()
          val samEdges = singleAbstractMethodInitEdge match {
            case s: Set[_] => s.asInstanceOf[Set[Int]]
            case other => other.toSet
          }
          val result = new Array[Int](ne.length + ceCard + samEdges.size)
          System.arraycopy(ne, 0, result, 0, ne.length)
          var pos = ne.length
          var bit = callbackEdges.nextSetBit(0)
          while (bit >= 0) {
            result(pos) = bit
            pos += 1
            bit = callbackEdges.nextSetBit(bit + 1)
          }
          for (s <- samEdges) {
            result(pos) = s
            pos += 1
          }
          result
      }
      .map(_.sorted)
      .toArray
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
   * Represents the two types of nodes in our call graph:
   * - LocalDef: a method definition in local code, with a code hash
   * - Call: a method call site, connecting to its resolved destinations
   *
   * External callback edges (from external code back into local code) are
   * computed precisely per-call-site by walking superclasses for method
   * signatures and subclasses for local classes, producing direct
   * LocalDef→LocalDef edges rather than going through shared proxy nodes.
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
}
