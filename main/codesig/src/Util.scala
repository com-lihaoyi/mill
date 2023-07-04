package mill.codesig

object Util {

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
  def computeTransitive[V: scala.reflect.ClassTag](
    topoSortedGroups: Array[Array[Int]],
    nodeEdges: Array[Array[Int]],
    nodeValue: Array[V],
    reduce: (V, V) => V,
    zero: V
  ) = {
    val nodeGroups = topoSortedGroups
      .iterator
      .zipWithIndex
      .flatMap{case (group, groupIndex) => group.map((_, groupIndex))}
      .toMap

    val seen = new Array[V](topoSortedGroups.length)
    for(groupIndex <- topoSortedGroups.indices){
      var value: V = zero
      for(node <- topoSortedGroups(groupIndex)){
        value = reduce(value, nodeValue(node))
        for(upstreamNode <- nodeEdges(node)){
          value = reduce(value, seen(nodeGroups(upstreamNode)))
        }
      }
      seen(groupIndex) = value
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
