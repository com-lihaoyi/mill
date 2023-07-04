package mill.codesig
import mill.util.Tarjans
import upickle.default.{Writer, macroW, writer}

object CallGraphAnalysis{
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

class CallGraphAnalysis(
                         val localSummary: LocalSummarizer.Result,
                         val resolved: MethodCallResolver.Result,
                         val methodHashes: Map[JType.Cls, Map[MethodSig, Int]],
                         val externalSummary: ExternalSummarizer.Result,
                         logger: Logger
                       ) {
  //  pprint.log(directCallGraph.size)
  //  pprint.log(directCallGraph.values.map(_.size).sum)
  val methodDefs = localSummary
    .items
    .flatMap { case (cls, cInfo) => cInfo.methods.map { case (m, mInfo) => MethodDef(cls, m) } }
    .toArray
    .distinct
    .sorted

  val methodCalls = resolved.localCalls.keys
  val externalClasses = externalSummary.directMethods.keys

  val indexToNodes: Array[CallGraphAnalysis.Node] =
    methodDefs.map[CallGraphAnalysis.Node](CallGraphAnalysis.LocalDef(_)) ++
      methodCalls.map(CallGraphAnalysis.Call(_)) ++
      externalClasses.map(CallGraphAnalysis.ExternalClsCall(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = indexToNodes
    .iterator
    .map {
      case CallGraphAnalysis.Call(methodCall) =>
        val callInfo = resolved.localCalls(methodCall)
        val local = callInfo.localDests.toArray.map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))
        val external = callInfo.externalDests.toArray.map(c => nodeToIndex(CallGraphAnalysis.ExternalClsCall(c)))
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
            } yield nodeToIndex(CallGraphAnalysis.LocalDef(MethodDef(cls, m)))
          }
          .toArray

        val parent = externalSummary
          .directAncestors(externalCls)
          .map(c => nodeToIndex(CallGraphAnalysis.ExternalClsCall(c)))

        local ++ parent
    }
    .toArray


  val indexGraphPrettyEdges = indexGraphEdges
    .zipWithIndex
    .map { case (dests, src) => indexToNodes(src) -> dests.map(indexToNodes) }
    .toMap
  logger.log(indexGraphPrettyEdges)

  lazy val prettyHashes = methodHashes
    .flatMap { case (k, vs) =>
      vs.map { case (m, dests) => MethodDef(k, m).toString -> dests }
    }

  val topoSortedMethodGroups =
    Tarjans.apply(indexGraphEdges.map(x => x: Iterable[Int])) // .map(_.map(indexToMethod).toSet)

  val nodeValues = indexToNodes.map{
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

  val prettyGroupHashes = groupTransitiveHashes.zipWithIndex.map{
    case (hash, index) => (
      topoSortedMethodGroups(index).map(indexToNodes(_).toString),
      topoSortedMethodGroups(index).map(indexGraphEdges(_).map(indexToNodes(_).toString)),
      hash
    )
  }
  logger.log(prettyGroupHashes)

  val transitiveCallGraphHashes = groupTransitiveHashes
    .zipWithIndex
    .flatMap{case (groupHash, groupIndex) =>
      topoSortedMethodGroups(groupIndex).map { nodeIndex =>
        (indexToNodes(nodeIndex), groupHash)
      }
    }
    .collect { case (CallGraphAnalysis.LocalDef(d), v) => (d.toString, v) }
    .toMap

  logger.log(transitiveCallGraphHashes)

  def simplifiedCallGraph[T](transform: PartialFunction[CallGraphAnalysis.Node, T]): Map[T, Set[T]] = {

    def flatten(ns: Set[CallGraphAnalysis.Node]): Set[T] = {
      if (ns.isEmpty) Set()
      else {
        val (notDefined, defined) = ns.partitionMap(n =>
          transform.lift(n) match {
            case None => Left(n)
            case Some(v) => Right(v)
          }
        )

        val downstream = flatten(
          notDefined.flatMap(n => indexGraphEdges(nodeToIndex(n))).map(indexToNodes)
        )

        defined ++ downstream
      }
    }

    indexGraphEdges
      .zipWithIndex
      .flatMap { case (destIndices, srcIndex) =>
        transform.lift(indexToNodes(srcIndex))
          .map((_, flatten(destIndices.map(destIndex => indexToNodes(destIndex)).toSet)))
      }
      .toMap
  }
}
