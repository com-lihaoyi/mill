package mill.codesig
import mill.util.Tarjans
import upickle.default.{Writer, macroW, writer}
import JvmModel._

class CallGraphAnalysis(
    localSummary: LocalSummarizer.Result,
    resolved: MethodCallResolver.Result,
    methodHashes: Map[JType.Cls, Map[MethodSig, Int]],
    externalSummary: ExternalSummarizer.Result,
    logger: Logger
)(implicit st: SymbolTable) {
  //  pprint.log(directCallGraph.size)
  //  pprint.log(directCallGraph.values.map(_.size).sum)
  val methodDefs = localSummary
    .items
    .flatMap { case (cls, cInfo) => cInfo.methods.map { case (m, mInfo) => st.MethodDef(cls, m) } }
    .toArray
    .distinct

  val methodCalls = resolved.localCalls.keys
  val externalClasses = externalSummary.directMethods.keys

  val indexToNodes: Array[CallGraphAnalysis.Node] =
    methodDefs.map[CallGraphAnalysis.Node](CallGraphAnalysis.LocalDef(_)) ++
    methodCalls.map(CallGraphAnalysis.Call(_)) ++
    externalClasses.map(CallGraphAnalysis.ExternalClsCall(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = CallGraphAnalysis.indexGraphEdges(
    indexToNodes,
    localSummary,
    resolved,
    externalSummary,
    nodeToIndex
  )

  val transitiveCallGraphHashes = CallGraphAnalysis.transitiveCallGraphHashes(
    indexGraphEdges,
    indexToNodes,
    methodHashes
  )

  logger.log(transitiveCallGraphHashes)


  def prettyHashes = methodHashes
    .flatMap { case (k, vs) =>
      vs.map { case (m, dests) => st.MethodDef(k, m).toString -> dests }
    }
}

object CallGraphAnalysis {
  def indexGraphEdges(indexToNodes: Array[Node],
                      localSummary: LocalSummarizer.Result,
                      resolved: MethodCallResolver.Result,
                      externalSummary: ExternalSummarizer.Result,
                      nodeToIndex: Map[CallGraphAnalysis.Node, Int])
                     (implicit st: SymbolTable)= {
    indexToNodes
      .iterator
      .map {
        case CallGraphAnalysis.Call(methodCall) =>
          val callInfo = resolved.localCalls(methodCall)
          val local = callInfo.localDests.toArray.map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))
          val external =
            callInfo.externalDests.toArray.map(c => nodeToIndex(CallGraphAnalysis.ExternalClsCall(c)))
          local ++ external

        case CallGraphAnalysis.LocalDef(methodDef) =>
          localSummary
            .get(methodDef.cls, methodDef.method)
            .get
            .calls
            .toArray
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
                if localSummary.get(cls, m).nonEmpty
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
  def transitiveCallGraphHashes(indexGraphEdges: Array[Array[Int]],
                                indexToNodes: Array[Node],
                                methodHashes: Map[JType.Cls, Map[MethodSig, Int]]) = {
    val topoSortedMethodGroups =
      Tarjans.apply(indexGraphEdges.map(x => x: Iterable[Int])) // .map(_.map(indexToMethod).toSet)

    val nodeValues = indexToNodes.map {
      case CallGraphAnalysis.LocalDef(m) => methodHashes(m.cls)(m.method)
      case _ => 0
    }
    val groupTransitiveHashes: Array[Int] = Util.computeTransitive[Int](
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
