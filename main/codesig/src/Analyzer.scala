package mill.codesig

import mill.util.{MultiBiMap, Tarjans}

object Analyzer{
  def analyze(summary: LocalSummarizer.Result, external: ExternalSummarizer.Result)  = {
    pprint.log(summary.callGraph.map{case (k, vs) => (k.toString, vs.map(_.toString))})
    val clsToMethods = summary.callGraph.keys.groupBy(_.cls)
    val methodToIndex = summary.callGraph.keys.toVector.zipWithIndex.toMap
    val indexToMethod = methodToIndex.map(_.swap)

    val allDirectAncestors = summary.directAncestors ++ external.directAncestors
    val directDescendents = allDirectAncestors
      .toVector
      .flatMap { case (k, vs) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)


    val externalClsToLocalClsMethods = summary
      .callGraph
      .keySet
      .map(_.cls)
      .flatMap { cls =>
        transitiveExternalMethods(cls, allDirectAncestors, external.directMethods).map {
          case (upstreamCls, localMethods) => (upstreamCls, Map(cls -> localMethods))
        }
      }
      .groupMapReduce(_._1)(_._2)(_ ++ _)

    val resolvedCalls = resolveAllCalls(
      summary.callGraph,
      methodToIndex,
      clsToMethods,
      externalClsToLocalClsMethods,
      allDirectAncestors,
      summary.directSubclasses,
      directDescendents
    )

    pprint.log(resolvedCalls.map{case (k, vs) => (k.toString, vs.map(_.toString))})

    val topoSortedMethodGroups = Tarjans
      .apply(
        Range(0, methodToIndex.size).map(i => resolvedCalls(indexToMethod(i)).map(methodToIndex))
      )
      .map(_.map(indexToMethod))

    val transitiveCallGraphHashes = computeTransitive[Int](
      topoSortedMethodGroups,
      resolvedCalls,
      summary.methodHashes(_),
      _.hashCode()
    )

    val transitiveCallGraphMethods = computeTransitive[Set[MethodSig]](
      topoSortedMethodGroups,
      resolvedCalls,
      Set(_),
      _.flatten.toSet
    ).map { case (k, vs) => (k, vs.filter(_ != k)) }

    transitiveCallGraphMethods
  }

  def resolveAllCalls(callGraph: Map[MethodSig, Set[MethodCall]],
                      methodToIndex: Map[MethodSig, Int],
                      clsToMethods: Map[JType.Cls, Iterable[MethodSig]],
                      externalClsToLocalClsMethods: Map[JType.Cls, Map[JType.Cls, Set[LocalMethodSig]]],
                      allDirectAncestors: Map[JType.Cls, Set[JType.Cls]],
                      directSubclasses: MultiBiMap[JType.Cls, JType.Cls],
                      directDescendents: Map[JType.Cls, Vector[JType.Cls]]): Map[MethodSig, Set[MethodSig]] = {


    def resolveLocalCall(call: MethodCall): Set[MethodSig] = {
      call.invokeType match {
        case InvokeType.Static =>
          clsAndSupers(
            call.cls,
            skipEarly = cls => clsToMethods.getOrElse(cls, Nil).exists(sigMatchesCall(_, call)),
            directSubclasses
          )
            .flatMap(clsToMethods.getOrElse(_, Nil))
            .find(sigMatchesCall(_, call))
            .toSet

        case InvokeType.Special => Set(MethodSig(call.cls, false, call.name, call.desc))
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
    }

    def resolveExternalCall(call: MethodCall): Set[MethodSig] = {

      call
        .desc
        .args
        .collect { case c: JType.Cls => externalClsToLocalClsMethods.getOrElse(c, Nil) }
        .flatten
        .flatMap { case (k, vs) => vs.map(m => MethodSig(k, m.static, m.name, m.desc)) }
        .toSet
    }

    for ((method, calls) <- callGraph)
    yield (
      method,
      calls
        .flatMap(call => resolveLocalCall(call) ++ resolveExternalCall(call))
        .filter(methodToIndex.contains)
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
                                externalDirectMethods: Map[JType.Cls, Set[LocalMethodSig]]): Map[JType.Cls, Set[LocalMethodSig]] = {
    allDirectAncestors(cls)
      .flatMap(transitiveExternalAncestors(_, allDirectAncestors))
      .map(cls => (cls, externalDirectMethods.getOrElse(cls, Set())))
      .toMap
  }

  def sigMatchesCall(sig: MethodSig, call: MethodCall) = {
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

  /**
   * Summarizes the transitive closure of the method call graph, using the given
   * [[methodValue]] and [[reduce]] functions to return a single value of [[T]].
   *
   * This is done in topological order, in order to allow us to memo-ize the
   * values computed for upstream methods when processing downstream methods,
   * avoiding the need to repeatedly re-compute them. Each Strongly Connected
   * Component is processed together and assigned the same final value, since
   * they all have the exact same transitive closure
   */
  def computeTransitive[T](topoSortedMethodGroups: Seq[Seq[MethodSig]],
                           resolvedCalls: Map[MethodSig, Set[MethodSig]],
                           methodValue: MethodSig => T, reduce: Seq[T] => T) = {
    val seen = collection.mutable.Map.empty[MethodSig, T]
    for (methodGroup <- topoSortedMethodGroups) {
      val groupUpstreamCalls = methodGroup
        .flatMap(resolvedCalls)
        .filter(!methodGroup.contains(_))

      val upstreamValues: Seq[T] = groupUpstreamCalls.sorted.map(seen)
      val groupValues: Seq[T] = methodGroup.sorted.map(methodValue)
      for (method <- methodGroup) {
        seen(method) = reduce(upstreamValues ++ groupValues)
      }
    }
    seen
  }
}
