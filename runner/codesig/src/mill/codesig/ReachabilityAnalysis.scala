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
      resolved.localCalls.keys.map(CallGraphAnalysis.Call(_)) ++
      externalSummary.directMethods.keys.map(CallGraphAnalysis.ExternalClsCall(_))

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

  logger.mandatoryLog(prettyCallGraph)

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

    val localDirectDescendents: Map[JType.Cls, Seq[JType.Cls]] = {
      val localAncestors =
        localSummary.mapValues(_.directAncestors.filter(localSummary.contains)).toMap
      SpanningForest.reverseEdges(localAncestors)
    }

    val localDirectAncestors: Map[JType.Cls, Set[JType.Cls]] =
      localSummary.mapValues(_.directAncestors.filter(localSummary.contains)).toMap

    indexToNodes
      .iterator
      .map {
        case CallGraphAnalysis.Call(methodCall) =>
          val callInfo = resolved.localCalls(methodCall)
          val local = callInfo
            .localDests
            .toArray
            .filter(methodDef => !singleAbstractMethods(methodDef.cls).contains(methodDef.sig))
            .map(d => nodeToIndex(CallGraphAnalysis.LocalDef(d)))

          // <clinit> calls model static initialization and should not produce
          // ExternalClsCall fan-out edges, since static initializers do not
          // call back into local subclass methods via virtual dispatch
          val external =
            if (methodCall.name == "<clinit>") Array.empty[Int]
            else callInfo
              .externalDests
              .toArray
              .map(c => nodeToIndex(CallGraphAnalysis.ExternalClsCall(c)))

          local ++ external

        case CallGraphAnalysis.LocalDef(methodDef) =>
          def isExternalPreciseThisCall(call: MethodCall): Boolean =
            call.invokeType == InvokeType.Special &&
              resolved.externalClassLocalDests.get(call.cls).exists(_._1.contains(methodDef.cls))

          def isExternalStaticReceiverCall(call: MethodCall): Boolean =
            call.invokeType == InvokeType.Static &&
              call.desc.args.headOption.contains(call.cls) &&
              resolved.externalClassLocalDests.get(call.cls).exists(_._1.nonEmpty)

          def descriptorExternalSelfArgClasses(call: MethodCall): Array[JType.Cls] =
            call.desc.args.collect {
              case c: JType.Cls
                  if resolved.externalClassLocalDests.get(c).exists(_._1.nonEmpty) =>
                c
            }.toArray

          def externalSelfArgClasses(call: MethodCall): Array[JType.Cls] = {
            val rawSlotTypes = methods(methodDef)
              .callRefArgSlotTypes
              .getOrElse(call, Set.empty)
            if (rawSlotTypes.nonEmpty) {
              val slotTypedClasses = rawSlotTypes.flatMap { c =>
                if (resolved.externalClassLocalDests.get(c).exists(_._1.nonEmpty)) {
                  // External class with local subclasses - use directly
                  Iterator.single(c)
                } else if (localSummary.contains(c)) {
                  // Local class - find its external ancestors that have local dests
                  localSummary.items(c).directAncestors.iterator
                    .flatMap(anc =>
                      SpanningForest
                        .breadthFirst(Seq(anc))(
                          externalSummary.directAncestors.getOrElse(_, Nil)
                        )
                    )
                    .filter(anc => resolved.externalClassLocalDests.get(anc).exists(_._1.nonEmpty))
                } else Iterator.empty
              }.toArray
              if (slotTypedClasses.nonEmpty) slotTypedClasses
              else descriptorExternalSelfArgClasses(call)
            } else descriptorExternalSelfArgClasses(call)
          }

          def isExternalKnownArgCall(call: MethodCall): Boolean =
            (call.invokeType == InvokeType.Static || call.invokeType == InvokeType.Special) &&
              resolved.localCalls(call).localDests.isEmpty &&
              resolved.localCalls(call).externalDests.nonEmpty &&
              externalSelfArgClasses(call).nonEmpty

          // Calls to external methods where we have precise arg type info showing
          // that no argument is a local class or has local subclasses. External
          // code cannot call back to any local method through these args, so the
          // imprecise ExternalClsCall fan-out is unnecessary.
          def isExternalSafeArgCall(call: MethodCall): Boolean = {
            val rawSlotTypes = methods(methodDef)
              .callRefArgSlotTypes
              .getOrElse(call, Set.empty)
            rawSlotTypes.nonEmpty &&
            resolved.localCalls(call).localDests.isEmpty &&
            resolved.localCalls(call).externalDests.nonEmpty &&
            rawSlotTypes.forall(c =>
              !localSummary.contains(c) &&
                !resolved.externalClassLocalDests.get(c).exists(_._1.nonEmpty)
            ) &&
            // For virtual calls, the receiver must also have no local subclasses,
            // since removing from normalCalls also drops receiver-side edges
            (call.invokeType != InvokeType.Virtual ||
              !resolved.externalClassLocalDests.get(call.cls).exists(_._1.nonEmpty))
          }

          def isExternalVirtualSelfCall(call: MethodCall): Boolean =
            call.invokeType == InvokeType.Virtual &&
              resolved.externalClassLocalDests.get(call.cls).exists(_._1.contains(methodDef.cls))

          def isLocalNoArgVirtualExternalReceiverCall(call: MethodCall): Boolean =
            call.invokeType == InvokeType.Virtual &&
              localSummary.contains(call.cls) &&
              call.desc.args.isEmpty &&
              resolved.localCalls(call).localDests.isEmpty &&
              resolved.localCalls(call).externalDests.nonEmpty

          val calls = methods(methodDef)
            .calls
            .toArray
            .filter(c => !ignoreCall(Some(methodDef), c.toMethodSig))

          val (externalPreciseThisCalls, remainingCalls) =
            calls.partition(isExternalPreciseThisCall)
          val (externalStaticReceiverCalls, remainingCalls2) =
            remainingCalls.partition(isExternalStaticReceiverCall)
          val (externalKnownArgCalls, remainingCalls3) =
            remainingCalls2.partition(isExternalKnownArgCall)
          val (_, remainingCalls3b) =
            remainingCalls3.partition(isExternalSafeArgCall)
          val (externalVirtualSelfCalls, remainingCalls4) =
            remainingCalls3b.partition(isExternalVirtualSelfCall)
          val (localNoArgVirtualExternalReceiverCalls, otherCalls) =
            remainingCalls4.partition(isLocalNoArgVirtualExternalReceiverCall)

          val normalCalls = otherCalls.map(c => nodeToIndex(CallGraphAnalysis.Call(c)))

          def concreteReceiverLocalMethodIndices(
              externalCls: JType.Cls,
              localReceiverCls: JType.Cls
          ): Array[Int] =
            mill.internal.SpanningForest
              .breadthFirst(Seq(externalCls))(externalSummary.directAncestors.getOrElse(_, Nil))
              .flatMap(externalSummary.directMethods.getOrElse(_, Map()).keysIterator)
              .filter(m => !m.static && m.name != "<init>")
              .filter(m => !singleAbstractMethods(localReceiverCls).contains(m))
              .filter(m => !ignoreCall(None, m))
              .flatMap { m =>
                nodeToIndex.get(CallGraphAnalysis.LocalDef(st.MethodDef(localReceiverCls, m)))
              }
              .toArray

          val externalPreciseThisCallbackCalls = externalPreciseThisCalls.flatMap { call =>
            concreteReceiverLocalMethodIndices(call.cls, methodDef.cls)
          }

          val externalStaticReceiverCallbackCalls = externalStaticReceiverCalls.flatMap { call =>
            // Use visitor-based arg0 types, then Analyzer-based ref arg types as fallback,
            // then fall back to the descriptor class
            val arg0Types =
              methods(methodDef).callArg0SlotTypes.getOrElse(call, Set.empty) match {
                case s if s.nonEmpty => s
                case _ =>
                  methods(methodDef).callRefArgSlotTypes.getOrElse(call, Set.empty) match {
                    case s if s.nonEmpty => s
                    case _ => Set(call.cls)
                  }
              }

            arg0Types.iterator.flatMap { arg0Type =>
              // If arg0 is a local class, resolve callbacks only for that class
              if (localSummary.contains(arg0Type)) {
                concreteReceiverLocalMethodIndices(call.cls, arg0Type).iterator
              } else {
                resolved.externalClassLocalDests
                  .get(arg0Type)
                  .iterator
                  .flatMap(_._1)
                  .flatMap(localReceiverCls =>
                    concreteReceiverLocalMethodIndices(call.cls, localReceiverCls)
                  )
              }
            }
          }

          val externalKnownArgCallbackCalls = externalKnownArgCalls.flatMap { call =>
            externalSelfArgClasses(call).flatMap { argType =>
              val localReceiverClasses =
                if (
                  call.invokeType == InvokeType.Special &&
                  resolved.externalClassLocalDests.get(argType).exists(_._1.contains(methodDef.cls))
                ) Iterator.single(methodDef.cls)
                else {
                  resolved.externalClassLocalDests
                    .get(argType)
                    .iterator
                    .flatMap(_._1)
                }

              localReceiverClasses
                .flatMap(localReceiverCls => concreteReceiverLocalMethodIndices(argType, localReceiverCls))
            }
          }

          val externalVirtualSelfCallbackCalls = externalVirtualSelfCalls.flatMap { call =>
            concreteReceiverLocalMethodIndices(call.cls, methodDef.cls)
          }

          val localNoArgVirtualExternalReceiverCallbackCalls =
            localNoArgVirtualExternalReceiverCalls.flatMap { call =>
              val methodDefAncestors =
                SpanningForest.breadthFirst(Seq(methodDef.cls))(
                  localDirectAncestors.getOrElse(_, Set.empty)
                ).toSet
              val receiverHierarchyRoot =
                if (methodDefAncestors.contains(call.cls)) methodDef.cls
                else call.cls

              val localReceiverHierarchy =
                SpanningForest.breadthFirst(Seq(receiverHierarchyRoot))(
                  localDirectDescendents.getOrElse(_, Seq.empty)
                )

              val externalReceiverClasses = resolved.localCalls(call).externalDests
                .filter(externalSummary.directMethods.contains)

              for {
                externalCls <- externalReceiverClasses
                receiverCls <- localReceiverHierarchy
                m <- SpanningForest
                  .breadthFirst(Seq(externalCls))(externalSummary.directAncestors.getOrElse(_, Nil))
                  .flatMap(externalSummary.directMethods.getOrElse(_, Map()).keysIterator)
                if !m.static && m.name != "<init>"
                if !singleAbstractMethods(receiverCls).contains(m)
                if !ignoreCall(None, m)
                dest <- nodeToIndex.get(CallGraphAnalysis.LocalDef(st.MethodDef(receiverCls, m)))
              } yield dest
            }

          val singleAbstractMethodInitEdge =
            if (methodDef.sig.name != "<init>") None
            else {
              singleAbstractMethods(methodDef.cls)
                .flatMap(samSig => nodeToIndex.get(LocalDef(st.MethodDef(methodDef.cls, samSig))))
            }

          normalCalls ++
            externalPreciseThisCallbackCalls ++
            externalStaticReceiverCallbackCalls ++
            externalKnownArgCallbackCalls ++
            externalVirtualSelfCallbackCalls ++
            localNoArgVirtualExternalReceiverCallbackCalls ++
            singleAbstractMethodInitEdge

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
                if !singleAbstractMethods(cls).contains(m)
                if !ignoreCall(None, m)
              } yield nodeToIndex(CallGraphAnalysis.LocalDef(st.MethodDef(cls, m)))
            }
            .toArray

          local
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
   * Represents the three types of nodes in our call graph. These are kept heterogeneous
   * because flattening them out into a homogenous graph of MethodDef -> MethodDef edges
   * results in a lot of duplication that bloats the size of the graph non-linearly with
   * the size of the program
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
  case class ExternalClsCall(call: JType.Cls) extends Node {
    override def toString: String = "external " + call.toString
  }
}
