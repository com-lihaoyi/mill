package mill.codesig
import collection.mutable

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

  case class Node(values: mutable.Map[Int, Node] = mutable.Map())
  def apply(indexGraphEdges: Array[Array[Int]],
            importantVertices: Set[Int]) = {
    // Find all importantVertices which are "roots" with no incoming edges
    // from other importantVertices
    val rootChangedNodeIndices = importantVertices.filter(i =>
      !indexGraphEdges(i).exists(importantVertices.contains(_))
    )

    // Prepare a mutable tree structure that we will return, pre-populated with
    // just the first level of nodes from the `rootChangedNodeIndices`, as well
    // as a `nodeMapping` to let us easily take any node index and directly look
    // up the node in the tree
    val nodeMapping = rootChangedNodeIndices.map((_, Node())).to(mutable.Map)
    val spanningForest = Node(nodeMapping.clone())

    // Do a breadth first search from the `rootChangedNodeIndices` across the
    // reverse edges of the graph to build up the spanning forest
    val downstreamGraphEdges = indexGraphEdges
      .zipWithIndex
      .flatMap { case (vs, k) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)

    ResolvedCalls.breadthFirst(rootChangedNodeIndices) { index =>
      val nextIndices = downstreamGraphEdges.getOrElse(index, Array())
      // We build up the spanningForest during a normal breadth first search,
      // using the `nodeMapping` to quickly find an vertice's tree node so we
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
}
