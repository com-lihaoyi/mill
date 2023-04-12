package mill.codesig

import mill.util.Tarjans

class Analyzer(summary: LocalSummarizer.Result, external: ExternalSummarizer.Result) {
  val clsToMethods = summary.callGraph.keys.groupBy(_.cls)
  val methodToIndex = summary.callGraph.keys.toVector.zipWithIndex.toMap
  val indexToMethod = methodToIndex.map(_.swap)

  val directDescendents = summary
    .directAncestors
    .toVector
    .flatMap { case (k, vs) => vs.map((_, k)) }
    .groupMap(_._1)(_._2)


  val externalClsToLocalClsMethods = summary
    .callGraph
    .keySet
    .map(_.cls)
    .flatMap { cls =>
      pprint.log(cls)
      pprint.log(transitiveExternalMethods(cls))
      transitiveExternalMethods(cls).map {
        case (upstreamCls, localMethods) => (upstreamCls, Map(cls -> localMethods))
      }
    }
    .groupMapReduce(_._1)(_._2)(_ ++ _)

  pprint.log(externalClsToLocalClsMethods)

  val resolvedCalls =
    for ((method, (hash, calls)) <- summary.callGraph)
    yield (method, calls.flatMap(resolveCall).filter(methodToIndex.contains))

  val topoSortedMethodGroups = Tarjans
    .apply(Range(0, methodToIndex.size).map(i => resolvedCalls(indexToMethod(i)).map(methodToIndex)))
    .map(_.map(indexToMethod))

  val transitiveCallGraphHashes =
    computeTransitive[Int](summary.callGraph(_)._1, _.hashCode())

  val transitiveCallGraphMethods =
    computeTransitive[Set[MethodSig]](Set(_), _.flatten.toSet)
      .map{case (k, vs) => (k, vs.filter(_ != k))}

  def sigMatchesCall(sig: MethodSig, call: MethodCall) = {
    sig.name == call.name && sig.desc == call.desc && (sig.static == (call.invokeType == InvokeType.Static))
  }
  def resolveCall(call: MethodCall): Set[MethodSig] = {
    if (clsToMethods.contains(call.cls)) resolveLocalCall(call)
    else resolveExternalCall(call)
  }

  def resolveLocalCall(call: MethodCall): Set[MethodSig] = {
    call.invokeType match {
      case InvokeType.Static =>
        clsAndSupers(
          call.cls,
          skipEarly = cls => clsToMethods.getOrElse(cls, Nil).exists(sigMatchesCall(_, call))
        )
          .flatMap(clsToMethods.getOrElse(_, Nil))
          .find(sigMatchesCall(_, call))
          .toSet

      case InvokeType.Special => Set(MethodSig(call.cls, false, call.name, call.desc))
      case InvokeType.Virtual =>
        val resolved = clsAndDescendents(call.cls)
          .flatMap(cls =>
            clsAndAncestors(
              cls,
              skipEarly = cls => clsToMethods.getOrElse(cls, Nil).exists(sigMatchesCall(_, call))
            )
          )
          .flatMap(clsToMethods.getOrElse(_, Nil))
          .filter(sigMatchesCall(_, call))

        resolved
    }
  }

  def transitiveExternalAncestors(cls: JType.Cls): Set[JType.Cls] = {
    Set(cls) ++
    external
      .directAncestors
      .getOrElse(cls, Set.empty[JType.Cls])
      .flatMap(transitiveExternalAncestors)
  }

  def transitiveExternalMethods(cls: JType.Cls): Map[JType.Cls, Set[LocalMethodSig]] = {
    summary.directAncestors(cls)
      .flatMap(transitiveExternalAncestors(_))
      .map(cls => (cls, external.directMethods.getOrElse(cls, Set())))
      .toMap
  }

  def resolveExternalCall(call: MethodCall): Set[MethodSig] = {

    val methods = call
      .desc
      .args
      .collect{case c: JType.Cls => externalClsToLocalClsMethods.getOrElse(c, Nil)}
      .flatten

    methods
      .flatMap{case (k, vs) => vs.map(m => MethodSig(k, m.static, m.name, m.desc))}
      .toSet
  }

  def clsAndSupers(cls: JType.Cls, skipEarly: JType.Cls => Boolean): Seq[JType.Cls] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else summary.directSubclasses.lookupValueOpt(cls)
    )
  }

  def clsAndAncestors(cls: JType.Cls, skipEarly: JType.Cls => Boolean): Set[JType.Cls] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else summary.directAncestors.getOrElse(cls, Nil)
    ).toSet
  }

  def clsAndDescendents(cls: JType.Cls): Set[JType.Cls] = {
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
  def computeTransitive[T](methodValue: MethodSig => T, reduce: Seq[T] => T) = {
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
