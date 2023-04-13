package mill.codesig

import JType.{Cls => JCls}

/**
 * Traverses the call graph and inheritance hierarchy summaries produced by
 * [[LocalSummarizer]] and [[ExternalSummarizer]] to resolve method calls to
 * their potential destinations and compute transitive properties of the
 * call graph
 */
object MethodCallResolver{
  def resolveAllMethodCalls(localSummary: LocalSummarizer.Result,
                            externalSummary: ExternalSummarizer.Result,
                            logger: Logger): Map[ResolvedMethodDef, Set[ResolvedMethodDef]]  = {

    val allDirectAncestors = logger{
      localSummary.directAncestors ++ externalSummary.directAncestors
    }

    val directDescendents = logger{
      allDirectAncestors
        .toVector
        .flatMap { case (k, vs) => vs.map((_, k)) }
        .groupMap(_._1)(_._2)
    }

    val externalClsToLocalClsMethods0 = logger{
      localSummary
        .callGraph
        .keySet
        .flatMap { cls =>
          transitiveExternalMethods(cls, allDirectAncestors, externalSummary.directMethods)
            .map { case (upstreamCls, localMethods) =>
              // <init> methods are final and cannot be overriden
              (upstreamCls, Map(cls -> localMethods.filter(m => !m.static && m.name != "<init>")))
            }
        }
        .groupMapReduce(_._1)(_._2)(_ ++ _)
    }

    // Make sure that when doing an external method call, we look up all
    // methods both defined on and inherited by the type in question, since
    // any of those could potentially get called by the external method
    val externalClsToLocalClsMethods = logger{
      externalClsToLocalClsMethods0.map{ case (cls, _) =>

        val all = clsAndAncestors(Seq(cls), _ => false, allDirectAncestors)
          .toVector
          .map(externalClsToLocalClsMethods0(_))

        val allKeys = all
          .flatMap(_.keys)
          .map((key: JCls) => (key, all.flatMap(_.get(key)).flatten.toSet))
          .toMap


        cls -> allKeys
      }
    }

    val resolvedCalls = resolveAllMethodCalls0(
      localSummary.callGraph,
      localSummary.methodPrivate,
      externalClsToLocalClsMethods,
      allDirectAncestors,
      localSummary.directSuperclasses ++ externalSummary.directSuperclasses,
      directDescendents,
      externalSummary.directMethods,
      logger
    )

    resolvedCalls
  }

  def resolveAllMethodCalls0(callGraph: Map[JCls, Map[MethodDef, Set[MethodCall]]],
                             methodPrivate: Map[JCls, Map[MethodDef, Boolean]],
                             externalClsToLocalClsMethods: Map[JCls, Map[JCls, Set[MethodDef]]],
                             allDirectAncestors: Map[JCls, Set[JCls]],
                             directSuperclasses: Map[JCls, JCls],
                             directDescendents: Map[JCls, Vector[JCls]],
                             externalDirectMethods: Map[JCls, Set[MethodDef]],
                             logger: Logger): Map[ResolvedMethodDef, Set[ResolvedMethodDef]] = {

    def methodExists(cls: JCls, call: MethodCall): Boolean = {
      callGraph.get(cls).exists(x => x.keys.exists(sigMatchesCall(_, call))) ||
      externalDirectMethods.get(cls).exists(_.exists(sigMatchesCall(_, call)))
    }

    def resolveLocalReceivers(call: MethodCall): Set[JCls] = call.invokeType match {
      case InvokeType.Static =>
        clsAndSupers(call.cls, methodExists(_, call), directSuperclasses)
          .find(methodExists(_, call))
          .toSet

      case InvokeType.Special => Set(call.cls)

      case InvokeType.Virtual =>
        val directDef = call.toDirectMethodDef
        if (methodPrivate.get(call.cls).exists(_.getOrElse(directDef, false))) Set(call.cls)
        else {
          val descendents = clsAndDescendents(call.cls, directDescendents)

          clsAndAncestors(descendents, methodExists(_, call), allDirectAncestors)
            .filter(methodExists(_, call))
        }
    }

    def resolveExternalLocalReceivers(invokeType: InvokeType,
                                      callDesc: Desc,
                                      called: Set[JCls]): Set[ResolvedMethodDef] = {
      val argTypes = callDesc.args.collect { case c: JCls => c }
      val thisTypes = if (invokeType == InvokeType.Static) Set.empty[JCls] else called


      (argTypes ++ thisTypes)
        .flatMap(externalClsToLocalClsMethods.getOrElse(_, Nil))
        .flatMap { case (k, vs) => vs.map(m => ResolvedMethodDef(k, m)) }
        .toSet
    }

    val allCalls = logger{ callGraph.toIterator.flatMap(_._2).flatMap(_._2).toSet }

    val resolvedMap = logger {
      allCalls
        .map { call =>

          val (localCandidates, externalCandidates) =
            resolveLocalReceivers(call).partition(callGraph.contains)

          val externalLocalResolvedMethods =
            if (externalCandidates.isEmpty) Set.empty[ResolvedMethodDef]
            else resolveExternalLocalReceivers(call.invokeType, call.desc, externalCandidates)

          val localResolvedMethods = localCandidates
            .map(ResolvedMethodDef(_, call.toDirectMethodDef))


          (call, localResolvedMethods ++ externalLocalResolvedMethods)
        }
        .toMap
    }

    val result = logger {
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
    result
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
    Util.breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else directSuperclasses.get(cls)
    )
  }

  def clsAndAncestors(classes: IterableOnce[JCls],
                      skipEarly: JCls => Boolean,
                      allDirectAncestors: Map[JCls, Set[JCls]]): Set[JCls] = {
    Util.breadthFirst(classes)(cls =>
      if(skipEarly(cls)) Nil else allDirectAncestors.getOrElse(cls, Nil)
    ).toSet
  }

  def clsAndDescendents(cls: JCls,
                        directDescendents: Map[JCls, Vector[JCls]]): Set[JCls] = {
    Util.breadthFirst(Seq(cls))(directDescendents.getOrElse(_, Nil)).toSet
  }

}
