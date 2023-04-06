package mill.codesig

import mill.util.Tarjans

class Analyzer(summary: Summary) {
  val clsToMethods = summary.callGraph.keys.groupBy(_.cls)
  val methodToIndex = summary.callGraph.keys.toVector.zipWithIndex.toMap
  val indexToMethod = methodToIndex.map(_.swap)

  val directDescendents = summary
    .directAncestors
    .toVector
    .flatMap { case (k, vs) => vs.map((_, k)) }
    .groupMap(_._1)(_._2)

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

  def clsAndSupers(cls: JType, skipEarly: JType => Boolean): Seq[JType] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else summary.directSubclasses.lookupValueOpt(cls)
    )
  }

  def clsAndAncestors(cls: JType, skipEarly: JType => Boolean): Set[JType] = {
    breadthFirst(Seq(cls))(cls =>
      if(skipEarly(cls)) Nil else summary.directAncestors.getOrElse(cls, Nil)
    ).toSet
  }

  def clsAndDescendents(cls: JType): Set[JType] = {
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
