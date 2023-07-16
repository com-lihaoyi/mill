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
    logger: Logger
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
  logger.log(indexGraphEdges)

  lazy val prettyMethods = methods.map { case (k, vs) => (k.toString, vs.codeHash) }.to(SortedMap)

  logger.log(prettyMethods)

  lazy val prettyGraph = {
    indexGraphEdges.zip(indexToNodes).map { case (vs, k) =>
      (upickle.default.writeJs(k).str, vs.map(indexToNodes))
    }
      .to(SortedMap)
  }

  logger.log(prettyGraph)

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

  logger.log(nodeValues)

  val transitiveCallGraphHashes = transitiveCallGraphValues[Int](
    nodeValues = nodeValues,
    reduce = _ + _,
    zero = 0
  )

  logger.log(transitiveCallGraphHashes)

  lazy val topoSortedGroupCallGraphHashes = {
    val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges)
    topoSortedMethodGroups.map(groupIndex =>
      groupIndex
        .flatMap(nodeIndex =>
          Seq(indexToNodes(nodeIndex)).collect { case CallGraphAnalysis.LocalDef(d) =>
            (d.toString, transitiveCallGraphHashes(d.toString))
          }
        )
        .to(SortedMap)
    )
  }

  logger.log(topoSortedGroupCallGraphHashes)
}

object CallGraphAnalysis {
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
      .collect { case (CallGraphAnalysis.LocalDef(d), v) => (d.toString, v) }
      .to(SortedMap)
  }

  /**
   * Represents the three types of nodes in our call graph. These are kept heterogenous
   * because flattening them out into a homogenous graph of MethodDef -> MethodDef edges
   * results in a lot of duplication that bloats the size of the graph non-linearly with
   * the size of the program
   */
  sealed trait Node

  implicit def nodeRw: Writer[Node] = upickle.default.stringKeyW(
    writer[String].comap[Node] {
      case LocalDef(call) => "def " + call.toString
      case Call(call) => "call " + call.toString
      case ExternalClsCall(call) => call.toString
    }
  )

  case class LocalDef(call: MethodDef) extends Node
  case class Call(call: MethodCall) extends Node
  case class ExternalClsCall(call: JType.Cls) extends Node
}
