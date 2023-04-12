package mill.codesig

import mill.util.{MultiBiMap, Tarjans}

/**
 * Traverses the call graph and inheritance hierarchy summaries produced by
 * [[LocalSummarizer]] and [[ExternalSummarizer]] to resolve method calls to
 * their potential destinations and compute transitive properties of the
 * call graph
 */
object Resolver{
  def resolveAllMethodCalls(localSummary: LocalSummarizer.Result,
                            externalSummary: ExternalSummarizer.Result): Map[MethodDef, Set[MethodDef]]  = {

    val clsToMethods = localSummary.callGraph.keys.groupBy(_.cls)

    val allDirectAncestors = localSummary.directAncestors ++ externalSummary.directAncestors
    val directDescendents = allDirectAncestors
      .toVector
      .flatMap { case (k, vs) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)


    val externalClsToLocalClsMethods = localSummary
      .callGraph
      .keySet
      .map(_.cls)
      .flatMap { cls =>
        transitiveExternalMethods(cls, allDirectAncestors, externalSummary.directMethods).map {
          case (upstreamCls, localMethods) => (upstreamCls, Map(cls -> localMethods))
        }
      }
      .groupMapReduce(_._1)(_._2)(_ ++ _)

    val resolvedCalls = resolveAllMethodCalls0(
      localSummary.callGraph,
      clsToMethods,
      externalClsToLocalClsMethods,
      allDirectAncestors,
      localSummary.directSubclasses,
      directDescendents
    )

    resolvedCalls
  }

  def resolveAllMethodCalls0(callGraph: Map[MethodDef, Set[MethodCall]],
                             clsToMethods: Map[JType.Cls, Iterable[MethodDef]],
                             externalClsToLocalClsMethods: Map[JType.Cls, Map[JType.Cls, Set[LocalMethodDef]]],
                             allDirectAncestors: Map[JType.Cls, Set[JType.Cls]],
                             directSubclasses: MultiBiMap[JType.Cls, JType.Cls],
                             directDescendents: Map[JType.Cls, Vector[JType.Cls]]): Map[MethodDef, Set[MethodDef]] = {


    def resolveLocalCall(call: MethodCall): Set[MethodDef] = call.invokeType match {
      case InvokeType.Static =>
        clsAndSupers(
          call.cls,
          skipEarly = cls => clsToMethods.getOrElse(cls, Nil).exists(sigMatchesCall(_, call)),
          directSubclasses
        )
          .flatMap(clsToMethods.getOrElse(_, Nil))
          .find(sigMatchesCall(_, call))
          .toSet

      case InvokeType.Special => Set(MethodDef(call.cls, false, call.name, call.desc))
      case InvokeType.Virtual =>
        val resolved = clsAndDescendents(call.cls, directDescendents)
          .flatMap(cls =>
            clsAndAncestors(
              cls,
              skipEarly = cls => clsToMethods.getOrElse(cls, Nil).exists(sigMatchesCall(_, call)),
              allDirectAncestors
            )
          )
          .flatMap(clsToMethods.getOrElse(_, Nil))
          .filter(sigMatchesCall(_, call))

        resolved
    }

    def resolveExternalCall(call: MethodCall): Set[MethodDef] = {
      call
        .desc
        .args
        .collect { case c: JType.Cls => externalClsToLocalClsMethods.getOrElse(c, Nil) }
        .flatten
        .flatMap { case (k, vs) => vs.map(m => MethodDef(k, m.static, m.name, m.desc)) }
        .filter(_.name != "<init>")
        .toSet
    }

    val allCalls = callGraph.flatMap(_._2).toSet

    val resolvedMap = allCalls
      .map(call => (call, resolveLocalCall(call) ++ resolveExternalCall(call)))
      .toMap

    for ((method, calls) <- callGraph)
    yield (method, calls.flatMap(resolvedMap).filter(callGraph.contains))
  }

  def transitiveExternalAncestors(cls: JType.Cls,
                                  allDirectAncestors: Map[JType.Cls, Set[JType.Cls]]): Set[JType.Cls] = {
    Set(cls) ++
    allDirectAncestors
      .getOrElse(cls, Set.empty[JType.Cls])
      .flatMap(transitiveExternalAncestors(_, allDirectAncestors))
  }

  def transitiveExternalMethods(cls: JType.Cls,
                                allDirectAncestors: Map[JType.Cls, Set[JType.Cls]],
                                externalDirectMethods: Map[JType.Cls, Set[LocalMethodDef]]): Map[JType.Cls, Set[LocalMethodDef]] = {
    allDirectAncestors(cls)
      .flatMap(transitiveExternalAncestors(_, allDirectAncestors))
      .map(cls => (cls, externalDirectMethods.getOrElse(cls, Set())))
      .toMap
  }

  def sigMatchesCall(sig: MethodDef, call: MethodCall) = {
    sig.name == call.name && sig.desc == call.desc && (sig.static == (call.invokeType == InvokeType.Static))
  }


  def clsAndSupers(cls: JType.Cls,
                   skipEarly: JType.Cls => Boolean,
                   directSubclasses: MultiBiMap[JType.Cls, JType.Cls]): Seq[JType.Cls] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else directSubclasses.lookupValueOpt(cls)
    )
  }

  def clsAndAncestors(cls: JType.Cls,
                      skipEarly: JType.Cls => Boolean,
                      allDirectAncestors: Map[JType.Cls, Set[JType.Cls]]): Set[JType.Cls] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else allDirectAncestors.getOrElse(cls, Nil)
    ).toSet
  }

  def clsAndDescendents(cls: JType.Cls,
                        directDescendents: Map[JType.Cls, Vector[JType.Cls]]): Set[JType.Cls] = {
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
