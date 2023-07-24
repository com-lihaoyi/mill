package mill.codesig
import mill.util.Tarjans
import upickle.default.{Writer, writer}
import JvmModel._

import scala.collection.immutable.SortedMap

class CallGraphAnalysis(
    localSummary: LocalSummary,
    resolved: ResolvedCalls,
    externalSummary: ExternalSummary,
    ignoreCall: (Option[MethodDef], MethodSig) => Boolean,
    logger: Logger,
    prevTransitiveCallGraphHashesOpt: () => Option[Map[String, Int]]
)(implicit st: SymbolTable) {

  val methods = for {
    (k, v) <- localSummary.items
    (sig, m) <- v.methods
  } yield (st.MethodDef(k, sig), m)

  val indexToNodes: Array[CallGraphAnalysis.Node] =
    methods.keys.toArray.map[CallGraphAnalysis.Node](CallGraphAnalysis.LocalDef(_)) ++
      resolved.localCalls.keys.map(CallGraphAnalysis.Call(_)) ++
      externalSummary.directMethods.keys.map(CallGraphAnalysis.ExternalClsCall(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = CallGraphAnalysis.indexGraphEdges(
    indexToNodes,
    methods,
    resolved,
    externalSummary,
    nodeToIndex,
    ignoreCall
  )

  lazy val methodCodeHashes = methods.map { case (k, vs) => (k.toString, vs.codeHash) }.to(SortedMap)

  logger.log(methodCodeHashes)

  lazy val prettyCallGraph = {
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
  ) = CallGraphAnalysis.transitiveCallGraphValues[V](
    indexGraphEdges,
    indexToNodes,
    nodeValues,
    reduce,
    zero
  )

  val nodeValues = indexToNodes.map {
    case CallGraphAnalysis.LocalDef(m) => methods(m).codeHash
    case _ => 0
  }

  val transitiveCallGraphHashes0 = transitiveCallGraphValues[Int](
    nodeValues = nodeValues,
    reduce = _ + _,
    zero = 0
  )
  val transitiveCallGraphHashes = transitiveCallGraphHashes0
    .collect { case (CallGraphAnalysis.LocalDef(d), v) => (d.toString, v) }
    .to(SortedMap)

  logger.log(transitiveCallGraphHashes)

  lazy val spanningInvalidationForest = prevTransitiveCallGraphHashesOpt() match{
    case Some(prevTransitiveCallGraphHashes) =>
      CallGraphAnalysis.spanningInvalidationForest(
        prevTransitiveCallGraphHashes,
        transitiveCallGraphHashes0,
        indexToNodes,
        indexGraphEdges
      )
    case None => ujson.Obj()
  }

  logger.log(spanningInvalidationForest)
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
  def spanningInvalidationForest(prevTransitiveCallGraphHashes: Map[String, Int],
                                 transitiveCallGraphHashes0: Array[(CallGraphAnalysis.Node, Int)],
                                 indexToNodes: Array[Node],
                                 indexGraphEdges: Array[Array[Int]]): ujson.Obj = {
    val transitiveCallGraphHashes0Map = transitiveCallGraphHashes0.toMap

    val nodesWithChangedHashes = indexGraphEdges
      .indices
      .filter { nodeIndex =>
        val currentValue = transitiveCallGraphHashes0Map(indexToNodes(nodeIndex))
        val prevValue = prevTransitiveCallGraphHashes.get(indexToNodes(nodeIndex).toString)

        !prevValue.contains(currentValue)
      }
      .toSet

    def spanningTreeToJsonTree(node: SpanningForest.Node): ujson.Obj = {
      ujson.Obj.from(
        node.values.map{ case (k, v) =>
          indexToNodes(k).toString -> spanningTreeToJsonTree(v)
        }
      )
    }

    spanningTreeToJsonTree(SpanningForest.apply(indexGraphEdges, nodesWithChangedHashes))
  }

  def indexGraphEdges(
      indexToNodes: Array[Node],
      methods: Map[MethodDef, LocalSummary.MethodInfo],
      resolved: ResolvedCalls,
      externalSummary: ExternalSummary,
      nodeToIndex: Map[CallGraphAnalysis.Node, Int],
      ignoreCall: (Option[MethodDef], MethodSig) => Boolean
  )(implicit st: SymbolTable) = {
    indexToNodes
      .iterator
      .map {
        case CallGraphAnalysis.Call(methodCall) =>
          val callInfo = resolved.localCalls(methodCall)
          val local =
            callInfo.localDests.toArray.map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))
          val external =
            callInfo.externalDests.toArray.map(c =>
              nodeToIndex(CallGraphAnalysis.ExternalClsCall(c))
            )
          local ++ external

        case CallGraphAnalysis.LocalDef(methodDef) =>
          methods(methodDef)
            .calls
            .toArray
            .filter(c => !ignoreCall(Some(methodDef), c.toMethodSig))
            .map(c => nodeToIndex(CallGraphAnalysis.Call(c)))

        case CallGraphAnalysis.ExternalClsCall(externalCls) =>
          val local = resolved
            .externalClassLocalDests
            .get(externalCls)
            .iterator
            .flatMap { case (localClasses: Set[JType.Cls], localMethods: Set[MethodSig]) =>
              for {
                cls <- localClasses
                m <- localMethods
                if methods.contains(st.MethodDef(cls, m))
                if !ignoreCall(None, m)
              } yield nodeToIndex(CallGraphAnalysis.LocalDef(st.MethodDef(cls, m)))
            }
            .toArray

          val parent = externalSummary
            .directAncestors(externalCls)
            .map(c => nodeToIndex(CallGraphAnalysis.ExternalClsCall(c)))

          local ++ parent
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
  ) = {
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
   * Represents the three types of nodes in our call graph. These are kept heterogenous
   * because flattening them out into a homogenous graph of MethodDef -> MethodDef edges
   * results in a lot of duplication that bloats the size of the graph non-linearly with
   * the size of the program
   */
  sealed trait Node

  implicit def nodeRw: Writer[Node] = upickle.default.stringKeyW(
    writer[String].comap[Node](_.toString)
  )

  case class LocalDef(call: MethodDef) extends Node{
    override def toString = "def " + call.toString
  }
  case class Call(call: MethodCall) extends Node{
    override def toString = "call " + call.toString
  }
  case class ExternalClsCall(call: JType.Cls) extends Node{
    override def toString = "external " + call.toString
  }
}
