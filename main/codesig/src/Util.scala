package mill.codesig

object Util{
  /**
   * Summarizes the transitive closure of the given topo-sorted graph, using the given
   * [[computeOutputValue]] and [[reduce]] functions to return a single value of [[T]].
   *
   * This is done in topological order, in order to allow us to memo-ize the
   * values computed for upstream groups when processing downstream methods,
   * avoiding the need to repeatedly re-compute them. Each Strongly Connected
   * Component is processed together and assigned the same final value, since
   * they all have the exact same transitive closure
   */
  def computeTransitive[T, V](topoSortedInputGroups: Seq[Set[T]],
                              edges: T => Set[T],
                              computeOutputValue: T => V,
                              reduce: Set[V] => V) = {
    val seen = collection.mutable.Map.empty[T, V]
    for (inputGroup <- topoSortedInputGroups) {
      val groupUpstreamEdges = inputGroup
        .flatMap(edges)
        .filter(!inputGroup.contains(_))

      val upstreamValues: Set[V] = groupUpstreamEdges.map(seen)
      val groupValues: Set[V] = inputGroup.map(computeOutputValue)
      for (method <- inputGroup) {
        seen(method) = reduce(upstreamValues ++ groupValues)
      }
    }
    seen
  }

  def breadthFirst[T](start: IterableOnce[T])(edges: T => IterableOnce[T]): Seq[T] = {
    val seen = collection.mutable.Set.empty[T]
    val seenList = collection.mutable.Buffer.empty[T]
    val queued = collection.mutable.Queue.from(start)

    while (queued.nonEmpty) {
      val current = queued.dequeue()
      seen.add(current)
      seenList.append(current)

      for (next <- edges(current)) {
        if (!seen.contains(next)) queued.enqueue(next)
      }
    }
    seenList.toSeq
  }
}
