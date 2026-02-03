package mill.internal

import scala.collection.mutable

/**
 * Algorithm to compute the minimal spanning forest of a directed acyclic graph
 * that covers a particular subset of [[importantVertices]] (a "Steiner Forest"),
 * minimizing the maximum height of the resultant trees. When multiple solutions
 * exist with the same height, one chosen is arbitrarily. (This is much simpler
 * than the "real" algorithm which aims to minimize the sum of edge/vertex weights)
 *
 * Returns the forest as a [[Node]] structure with the top-level node containing
 * the roots of the forest
 */
object SpanningForest {

  def graphMapToIndices[T](
      vertices: Iterable[T],
      edges: Map[T, Seq[T]]
  ): (Map[T, Int], Array[Array[Int]]) = {
    val vertexToIndex = vertices.zipWithIndex.toMap
    val edgeIndices = vertices
      .map(t => edges.getOrElse(t, Nil).flatMap(vertexToIndex.get(_)).toArray)
      .toArray

    (vertexToIndex, edgeIndices)
  }

  def writeJsonFile(
      path: os.Path,
      indexEdges: Array[Array[Int]],
      interestingIndices: Set[Int],
      render: Int => String
  ): Unit = {
    val json = writeJson(indexEdges, interestingIndices, render).render(indent = 2)
    os.write.over(path, json)
  }

  def writeJson(
      indexEdges: Array[Array[Int]],
      interestingIndices: Set[Int],
      render: Int => String
  ): ujson.Obj = {
    val forest = SpanningForest.applyInferRoots(indexEdges, interestingIndices)
    SpanningForest.spanningTreeToJsonTree(forest, render)
  }

  def spanningTreeToJsonTree(node: SpanningForest.Node, stringify: Int => String): ujson.Obj = {
    val entries = node.values.toSeq.sortBy(_._1).map { case (k, v) =>
      stringify(k) -> spanningTreeToJsonTree(v, stringify)
    }
    ujson.Obj.from(entries)
  }

  case class Node(values: mutable.LinkedHashMap[Int, Node] = mutable.LinkedHashMap())

  /**
   * Build spanning forest with explicitly provided roots.
   */
  def applyWithRoots(
      indexGraphEdges: Array[Array[Int]],
      rootsOrdered: Seq[Int],
      importantVertices: Set[Int]
  ): Node = {
    // Prepare a mutable tree structure, pre-populated with the root nodes,
    // as well as a `nodeMapping` to let us easily take any node index and
    // directly look up the node in the tree
    val rootNodeIndices = rootsOrdered.filter(importantVertices.contains)
    val nodeMapping = rootNodeIndices.map((_, Node())).to(mutable.Map)
    val spanningForest = Node(mutable.LinkedHashMap.from(nodeMapping))

    // Do a breadth first search from the root nodes across the graph edges
    // to build up the spanning forest
    breadthFirst(rootNodeIndices) { index =>
      val nextIndices = indexGraphEdges(index).filter(importantVertices).sorted

      // We build up the spanningForest during a normal breadth first search,
      // using the `nodeMapping` to quickly find a vertice's tree node so we
      // can add children to it. We need to duplicate the `seen.contains` logic
      // in `!nodeMapping.contains`, because `breadthFirst` does not expose it
      for (nextIndex <- nextIndices if !nodeMapping.contains(nextIndex)) {
        val node = Node()
        nodeMapping(nextIndex) = node
        nodeMapping(index).values(nextIndex) = node
      }
      nextIndices
    }
    spanningForest
  }

  /**
   * Build spanning forest, inferring roots as importantVertices with no incoming
   * edges from other importantVertices.
   */
  def applyInferRoots(indexGraphEdges: Array[Array[Int]], importantVertices: Set[Int]): Node = {
    val destinations = importantVertices.flatMap(indexGraphEdges(_))
    val roots = importantVertices.filter(!destinations.contains(_)).toSeq.sorted
    applyWithRoots(indexGraphEdges, roots, importantVertices)
  }

  def breadthFirst[T](start: IterableOnce[T])(edges: T => IterableOnce[T]): Seq[T] = {
    val seen = collection.mutable.Set.empty[T]
    val seenList = collection.mutable.Buffer.empty[T]
    val queued = collection.mutable.Queue.empty[T]

    // Add starting nodes, deduplicating and tracking in seen
    for (s <- start.iterator if seen.add(s)) queued.enqueue(s)

    while (queued.nonEmpty) {
      val current = queued.dequeue()
      seenList.append(current)

      for (next <- edges(current).iterator if seen.add(next)) queued.enqueue(next)
    }
    seenList.toSeq
  }

  def reverseEdges[T, V](edges: Iterable[(T, Iterable[V])]): Map[V, Vector[T]] = {
    val flatEdges = edges.iterator.flatMap { case (k, vs) => vs.map(_ -> k) }.toVector
    flatEdges
      .groupMap(_._1)(_._2)
      .map { case (k, v) => (k, v.toVector.sortBy(_.toString)) }
      .toMap
  }
}
