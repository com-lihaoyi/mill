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

    val resolvedCalls = resolveAllMethodCalls0(
      localSummary.callGraph,
      externalClsToLocalClsMethods,
      allDirectAncestors,
      localSummary.directSubclasses,
      directDescendents
    )

    resolvedCalls
  }

  def resolveAllMethodCalls0(callGraph: Map[JType.Cls, Map[MethodDef, Set[MethodCall]]],
                             externalClsToLocalClsMethods: Map[JType.Cls, Map[JType.Cls, Set[MethodDef]]],
                             allDirectAncestors: Map[JType.Cls, Set[JType.Cls]],
                             directSubclasses: MultiBiMap[JType.Cls, JType.Cls],
                             directDescendents: Map[JType.Cls, Vector[JType.Cls]]): Map[ResolvedMethodDef, Set[ResolvedMethodDef]] = {


    def methodExists(cls: JType.Cls, call: MethodCall): Boolean = {
      callGraph.get(cls).exists(x => x.keys.exists(sigMatchesCall(_, call)))
    }

    def resolveLocalCall(call: MethodCall): Set[ResolvedMethodDef] = call.invokeType match {
      case InvokeType.Static =>
        val clsAndSupers0 = clsAndSupers(
          call.cls,
          skipEarly = methodExists(_, call),
          directSubclasses
        )

        val resolvedStatic = clsAndSupers0
          .collectFirst {case cls if methodExists(cls, call) =>
            ResolvedMethodDef(cls, MethodDef(true, call.name, call.desc))
          }
          .toSet

        resolvedStatic

      case InvokeType.Special =>
        Set(ResolvedMethodDef(call.cls, MethodDef(false, call.name, call.desc)))

      case InvokeType.Virtual =>
        val resolved = clsAndDescendents(call.cls, directDescendents)
          .flatMap(cls =>
            clsAndAncestors(
              cls,
              skipEarly = methodExists(_, call),
              allDirectAncestors
            )
          )
          .collect { case cls if methodExists(cls, call) =>
            ResolvedMethodDef(cls, MethodDef(false, call.name, call.desc))
          }

        resolved
    }

    def resolveExternalCall(call: MethodCall): Set[ResolvedMethodDef] = {
      val externalArgTypes = call
        .desc
        .args
        .collect { case c: JType.Cls => externalClsToLocalClsMethods.getOrElse(c, Nil) }
        .flatten

      val externalThisType = externalClsToLocalClsMethods.getOrElse(call.cls, Map.empty)

      (externalArgTypes ++ externalThisType)
        .flatMap { case (k, vs) => vs.map(m => ResolvedMethodDef(k, MethodDef(m.static, m.name, m.desc))) }
        .filter(_.method.name != "<init>")
        .toSet
    }

    val allCalls = callGraph.toIterator.flatMap(_._2).flatMap(_._2).toSet

    val resolvedMap = allCalls
      .map(call => (call, resolveLocalCall(call) ++ resolveExternalCall(call)))
      .toMap

    for {
      (cls, methods) <- callGraph
      (m0, calls) <- methods
    } yield (
      ResolvedMethodDef(cls, m0),
      calls
        .flatMap(resolvedMap.getOrElse(_, Nil))
        .filter{m => callGraph.getOrElse(m.cls, Map()).contains(m.method)}
    )
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
                                externalDirectMethods: Map[JType.Cls, Set[MethodDef]]): Map[JType.Cls, Set[MethodDef]] = {
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
