package mill.codesig

import mill.util.{MultiBiMap, Tarjans}
import JType.{Cls => JCls}

/**
 * Traverses the call graph and inheritance hierarchy summaries produced by
 * [[LocalSummarizer]] and [[ExternalSummarizer]] to resolve method calls to
 * their potential destinations and compute transitive properties of the
 * call graph
 */
object MethodCallResolver{
  def resolveAllMethodCalls(localSummary: LocalSummarizer.Result,
                            externalSummary: ExternalSummarizer.Result): Map[ResolvedMethodDef, Set[ResolvedMethodDef]]  = {

    val allDirectAncestors = localSummary.directAncestors ++ externalSummary.directAncestors
    val directDescendents = allDirectAncestors
      .toVector
      .flatMap { case (k, vs) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)


    val externalClsToLocalClsMethods = localSummary
      .callGraph
      .keySet
      .flatMap { cls =>
        transitiveExternalMethods(cls, allDirectAncestors, externalSummary.directMethods)
          .map { case (upstreamCls, localMethods) =>
            (upstreamCls, Map(cls -> localMethods.filter(m => !m.static && m.name != "<init>")))
          }
      }
      .groupMapReduce(_._1)(_._2)(_ ++ _)

    new MultiBiMap.Mutable()
    val resolvedCalls = resolveAllMethodCalls0(
      localSummary.callGraph,
      externalClsToLocalClsMethods,
      allDirectAncestors,
      localSummary.directSuperclasses ++ externalSummary.directSuperclasses,
      directDescendents,
      externalSummary.directMethods
    )

    resolvedCalls
  }

  def resolveAllMethodCalls0(callGraph: Map[JCls, Map[MethodDef, Set[MethodCall]]],
                             externalClsToLocalClsMethods: Map[JCls, Map[JCls, Set[MethodDef]]],
                             allDirectAncestors: Map[JCls, Set[JCls]],
                             directSuperclasses: Map[JCls, JCls],
                             directDescendents: Map[JCls, Vector[JCls]],
                             externalDirectMethods: Map[JCls, Set[MethodDef]]): Map[ResolvedMethodDef, Set[ResolvedMethodDef]] = {

    def methodExists(cls: JCls, call: MethodCall): Boolean = {
      callGraph.get(cls).exists(x => x.keys.exists(sigMatchesCall(_, call))) ||
      externalDirectMethods.get(cls).exists(_.exists(sigMatchesCall(_, call)))
    }

    def resolveLocalReceivers(call: MethodCall): Set[JCls] = call.invokeType match {
      case InvokeType.Static =>
        val candidates = clsAndSupers(
          call.cls,
          skipEarly = methodExists(_, call),
          directSuperclasses
        )

        candidates.find(methodExists(_, call)).toSet

      case InvokeType.Special => Set(call.cls)

      case InvokeType.Virtual =>
        val candidates = clsAndAncestors(
          clsAndDescendents(call.cls, directDescendents),
          skipEarly = methodExists(_, call),
          allDirectAncestors
        )

        candidates.filter(methodExists(_, call))
    }

    def resolveExternalLocalReceivers(callDesc: Desc, called: Set[JCls]): Set[ResolvedMethodDef] = {
      val argTypes = callDesc.args.collect { case c: JCls => c }
      val thisTypes = called

      (argTypes ++ thisTypes)
        .flatMap(externalClsToLocalClsMethods.getOrElse(_, Nil))
        .flatMap { case (k, vs) => vs.map(m => ResolvedMethodDef(k, m)) }
        .filter(_.method.name != "<init>")
        .toSet
    }

    val allCalls = callGraph.toIterator.flatMap(_._2).flatMap(_._2).toSet

    val resolvedMap = allCalls
      .map{ call =>
        val (localCandidates, externalCandidates) =
          resolveLocalReceivers(call).partition(callGraph.contains)

        val externalLocalResolvedMethods =
          if (externalCandidates.isEmpty) {
            Set.empty[ResolvedMethodDef]
          } else {
            resolveExternalLocalReceivers(call.desc, externalCandidates)
          }

        val localResolvedMethods = localCandidates
          .map(ResolvedMethodDef(_, MethodDef(call.invokeType == InvokeType.Static, call.name, call.desc)))

        (call, localResolvedMethods ++ externalLocalResolvedMethods)
      }
      .toMap

    for {
      (cls, methods) <- callGraph
      (m0, calls) <- methods
    } yield {
      val resolvedMethod = ResolvedMethodDef(cls, m0)
      val resolved = calls
        .flatMap(resolvedMap.getOrElse(_, Nil))
        .filter { m => callGraph.getOrElse(m.cls, Map()).contains(m.method) }

      (resolvedMethod, resolved)
    }
  }

  def transitiveExternalAncestors(cls: JCls,
                                  allDirectAncestors: Map[JCls, Set[JCls]]): Set[JCls] = {
    Set(cls) ++
    allDirectAncestors
      .getOrElse(cls, Set.empty[JCls])
      .flatMap(transitiveExternalAncestors(_, allDirectAncestors))
  }

  def transitiveExternalMethods(cls: JCls,
                                allDirectAncestors: Map[JCls, Set[JCls]],
                                externalDirectMethods: Map[JCls, Set[MethodDef]]): Map[JCls, Set[MethodDef]] = {
    allDirectAncestors(cls)
      .flatMap(transitiveExternalAncestors(_, allDirectAncestors))
      .map(cls => (cls, externalDirectMethods.getOrElse(cls, Set())))
      .toMap
  }

  def sigMatchesCall(sig: MethodDef, call: MethodCall) = {
    sig.name == call.name && sig.desc == call.desc && (sig.static == (call.invokeType == InvokeType.Static))
  }


  def clsAndSupers(cls: JCls,
                   skipEarly: JCls => Boolean,
                   directSuperclasses: Map[JCls, JCls]): Seq[JCls] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else directSuperclasses.get(cls)
    )
  }

  def clsAndAncestors(classes: IterableOnce[JCls],
                      skipEarly: JCls => Boolean,
                      allDirectAncestors: Map[JCls, Set[JCls]]): Set[JCls] = {
    breadthFirst(classes)(cls =>
      if(skipEarly(cls)) Nil else allDirectAncestors.getOrElse(cls, Nil)
    ).toSet
  }

  def clsAndDescendents(cls: JCls,
                        directDescendents: Map[JCls, Vector[JCls]]): Set[JCls] = {
    breadthFirst(Seq(cls))(directDescendents.getOrElse(_, Nil)).toSet
  }

  def breadthFirst[T](start: IterableOnce[T])(edges: T => IterableOnce[T]): Seq[T] = {
    val seen = collection.mutable.Set.empty[T]
    val seenList = collection.mutable.Buffer.empty[T]
    val queued = collection.mutable.Queue.from(start)

    while(queued.nonEmpty){
      val current = queued.dequeue()
      seen.add(current)
      seenList.append(current)

      for(next <- edges(current)){
        if (!seen.contains(next)) queued.enqueue(next)
      }
    }
    seenList.toSeq
  }

}
