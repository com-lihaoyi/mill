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

    def resolveLocalCall(call: MethodCall): Set[ResolvedMethodDef] = call.invokeType match {
      case InvokeType.Static =>
        val clsAndSupers0 = clsAndSupers(
          call.cls,
          skipEarly = methodExists(_, call),
          directSuperclasses
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
        val resolved = clsAndAncestors(
          clsAndDescendents(call.cls, directDescendents).toSeq,
          skipEarly = methodExists(_, call),
          allDirectAncestors
        )
          .collect { case cls if methodExists(cls, call) =>
            ResolvedMethodDef(cls, MethodDef(false, call.name, call.desc))
          }

        resolved
    }

    def resolveExternalCall(called: Set[ResolvedMethodDef]): Set[ResolvedMethodDef] = {
      val argTypes = called.flatMap(_.method.desc.args).collect{case c: JCls => c}
      val thisTypes = called.map(_.cls)

      val allExternalTypes = (argTypes ++ thisTypes)
        .flatMap(externalClsToLocalClsMethods.getOrElse(_, Nil))

      allExternalTypes
        .flatMap { case (k, vs) => vs.map(m => ResolvedMethodDef(k, MethodDef(m.static, m.name, m.desc))) }
        .filter(_.method.name != "<init>")
    }

    val allCalls = callGraph.toIterator.flatMap(_._2).flatMap(_._2).toSet

    val resolvedMap = allCalls
      .map{ call =>
        val (localCandidates, externalCandidates0) =
          resolveLocalCall(call).partition(call => callGraph.contains(call.cls))

        val externalCandidates = externalCandidates0.filter(!_.method.static)

        (call, localCandidates ++ resolveExternalCall(externalCandidates))
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

  def clsAndAncestors(classes: Seq[JCls],
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
