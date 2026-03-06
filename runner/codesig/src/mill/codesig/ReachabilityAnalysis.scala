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

  lazy val methodCodeHashes: SortedMap[String, Int] =
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

  logger.log(transitiveCallGraphHashes0)
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

  logger.mandatoryLog(spanningInvalidationTree)
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

    val reverseGraphMap = indexGraphEdges
      .zipWithIndex
      .flatMap { case (vs, k) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)

    val reverseGraphEdges =
      indexGraphEdges.indices.map(reverseGraphMap.getOrElse(_, Array[Int]())).toArray

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

    // Cache ancestor BFS results (as Sets for O(1) contains checks)
    val ancestorCache = collection.mutable.Map.empty[JType.Cls, Set[JType.Cls]]
    def getTransitiveAncestors(extType: JType.Cls): Set[JType.Cls] = {
      ancestorCache.getOrElseUpdate(
        extType,
        SpanningForest.breadthFirst(Seq(extType)) { c =>
          if (localSummary.contains(c)) localSummary.items(c).directAncestors
          else externalSummary.directAncestors.getOrElse(c, Nil)
        }.toSet
      )
    }

    // Precompute callback indices per (extType, localSub) pair. For a given
    // extType, walks its ancestors to collect callback method signatures, then
    // for each localSub resolves the valid node indices (filtering SAM/ignoreCall).
    // This is independent of the call site, so computed once and reused.
    val callbackIndicesCache =
      collection.mutable.Map.empty[JType.Cls, (Iterable[JType.Cls], Map[JType.Cls, Array[Int]])]
    def getCallbackInfo(
        extType: JType.Cls
    ): (Iterable[JType.Cls], Map[JType.Cls, Array[Int]]) = {
      callbackIndicesCache.getOrElseUpdate(
        extType, {
          val localSubs: Iterable[JType.Cls] =
            resolved.externalClassLocalDests.get(extType).map(_._1).getOrElse(
              if (localSummary.contains(extType)) Seq(extType) else Nil
            )
          if (localSubs.isEmpty) (localSubs, Map.empty)
          else {
            // Collect all callback method signatures by walking ancestors
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

            (localSubs, subToIndices)
          }
        }
      )
    }

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
          // For calls to external methods, compute precise callback edges by
          // walking superclasses for method signatures and subclasses for local
          // classes, then adding direct LocalDef→LocalDef edges for each
          // (method, localSubclass) pair.

          // For a call with external dests, computes precise callback edges
          // and includes local dests directly, bypassing the Call node entirely.
          // For calls without external dests, returns None to use the Call node.
          def resolveCallbackEdges(call: MethodCall): Option[Set[Int]] = {
            val callInfo = resolved.localCalls(call)
            if (callInfo.externalDests.isEmpty) return None

            val indices = collection.mutable.Set.empty[Int]

            // Include local dests directly (same filtering as the Call node case)
            for {
              dest <- callInfo.localDests
              if !singleAbstractMethods(dest.cls).contains(dest.sig)
            } indices += nodeToIndex(CallGraphAnalysis.LocalDef(dest))

            val methodInfo = methods(methodDef)
            val argTypes =
              methodInfo.callSiteArgTypes.getOrElse(call, Set.empty)
            val receiverType: Option[JType.Cls] =
              methodInfo.callSiteReceiverType.get(call)

            // Arg types expand the set of external types to search (the external
            // method could call back on args). Receiver type is only for narrowing.
            val allExtTypes =
              if (argTypes.nonEmpty) callInfo.externalDests ++ argTypes
              else callInfo.externalDests

            // All precise types (receiver + args + caller class) for narrowing local
            // subclasses. The caller's class is included because methods typically
            // interact with their own class through `this`, even when the analyzer
            // can't determine the precise type (e.g. lambdas capturing `this` as a
            // generic Module reference due to field type erasure).
            val allPreciseTypes: Set[JType.Cls] = argTypes ++ receiverType + methodDef.cls

            for (extType <- allExtTypes) {
              val (localSubs, subToIndices) = getCallbackInfo(extType)
              for {
                s <- localSubs
                if allPreciseTypes.exists(pt =>
                  pt == s || getTransitiveAncestors(s).contains(pt)
                )
                idx <- subToIndices.getOrElse(s, Array.empty[Int])
              } indices += idx
            }

            Some(indices.toSet)
          }

          val calls = methods(methodDef)
            .calls
            .toArray
            .filter(c => !ignoreCall(Some(methodDef), c.toMethodSig))

          val normalEdges = Array.newBuilder[Int]
          val callbackEdges = collection.mutable.Set.empty[Int]
          for (call <- calls) {
            resolveCallbackEdges(call) match {
              case Some(edges) => callbackEdges ++= edges
              case None => normalEdges += nodeToIndex(CallGraphAnalysis.Call(call))
            }
          }

          val singleAbstractMethodInitEdge =
            if (methodDef.sig.name != "<init>") None
            else {
              singleAbstractMethods(methodDef.cls)
                .flatMap(samSig => nodeToIndex.get(LocalDef(st.MethodDef(methodDef.cls, samSig))))
            }

          normalEdges.result() ++ callbackEdges ++ singleAbstractMethodInitEdge
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

    val nodeGroups = topoSortedMethodGroups
      .iterator
      .zipWithIndex
      .flatMap { case (group, groupIndex) => group.map((_, groupIndex)) }
      .toMap

    val seenGroupValues = new Array[V](topoSortedMethodGroups.length)
    for (groupIndex <- topoSortedMethodGroups.indices) {
      var value: V = zero
      for (node <- topoSortedMethodGroups(groupIndex)) {
        value = reduce(value, nodeValues(node))
        for (upstreamNode <- indexGraphEdges(node)) {
          val upstreamGroup = nodeGroups(upstreamNode)
          if (upstreamGroup != groupIndex) {
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
