package mill.codesig
import mill.util.Tarjans
import upickle.default.{Writer, writer}
import JvmModel._

class CallGraphAnalysis(
    localSummary: LocalSummary,
    resolved: ResolvedCalls,
    externalSummary: ExternalSummary,
    ignoreCall: (Option[Set[MethodCall]], MethodSig) => Boolean,
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

  lazy val prettyGraph = {
    indexGraphEdges.zip(indexToNodes).map { case (vs, k) => (k, vs.map(indexToNodes)) }.toMap
  }

  logger.log(prettyGraph)

  val transitiveCallGraphHashes = CallGraphAnalysis.transitiveCallGraphHashes(
    indexGraphEdges,
    indexToNodes,
    methods
  )

  logger.log(transitiveCallGraphHashes)
}

object CallGraphAnalysis {
  def indexGraphEdges(
      indexToNodes: Array[Node],
      methods: Map[MethodDef, LocalSummary.MethodInfo],
      resolved: ResolvedCalls,
      externalSummary: ExternalSummary,
      nodeToIndex: Map[CallGraphAnalysis.Node, Int],
      ignoreCall: (Option[Set[MethodCall]], MethodSig) => Boolean
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
            .filter(c => !ignoreCall(Some(methods(methodDef).calls), c.toMethodSig))
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
      .toArray
  }
  def transitiveCallGraphHashes(
      indexGraphEdges: Array[Array[Int]],
      indexToNodes: Array[Node],
      methods: Map[MethodDef, LocalSummary.MethodInfo]
  ) = {
    val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges)

    val nodeValues = indexToNodes.map {
      case CallGraphAnalysis.LocalDef(m) => methods(m).codeHash
      case _ => 0
    }
    val groupTransitiveHashes: Array[Int] = computeTransitive[Int](
      topoSortedMethodGroups,
      indexGraphEdges,
      nodeValues,
      reduce = _ + _,
      zero = 0
    )

    groupTransitiveHashes
      .zipWithIndex
      .flatMap { case (groupHash, groupIndex) =>
        topoSortedMethodGroups(groupIndex).map { nodeIndex =>
          (indexToNodes(nodeIndex), groupHash)
        }
      }
      .collect { case (CallGraphAnalysis.LocalDef(d), v) => (d.toString, v) }
      .toMap
  }

  /**
   * Summarizes the transitive closure of the given topo-sorted graph, using the given
   * [[computeOutputValue]] and [[reduce]] functions to return a single value of [[T]].
   *
   * This is done in topological order, in order to allow us to memo-ize the
   * values computed for upstream groups when processing downstream methods,
   * avoiding the need to repeatedly re-compute them. Each Strongly Connected
   * Component is processed together and assigned the same final value, since
   * they all have the exact same transitive closure
   */
  def computeTransitive[V: scala.reflect.ClassTag](
      topoSortedGroups: Array[Array[Int]],
      nodeEdges: Array[Array[Int]],
      nodeValue: Array[V],
      reduce: (V, V) => V,
      zero: V
  ) = {
    val nodeGroups = topoSortedGroups
      .iterator
      .zipWithIndex
      .flatMap { case (group, groupIndex) => group.map((_, groupIndex)) }
      .toMap

    val seen = new Array[V](topoSortedGroups.length)
    for (groupIndex <- topoSortedGroups.indices) {
      var value: V = zero
      for (node <- topoSortedGroups(groupIndex)) {
        value = reduce(value, nodeValue(node))
        for (upstreamNode <- nodeEdges(node)) {
          value = reduce(value, seen(nodeGroups(upstreamNode)))
        }
      }
      seen(groupIndex) = value
    }
    seen
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
