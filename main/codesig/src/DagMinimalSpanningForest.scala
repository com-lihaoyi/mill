package mill.codesig
import collection.mutable

/**
 * Algorithm to compute the minimal spanning forest of a directed acyclic graph
 * that covers a particular set of [[importantVertices]] minimizing the height
 * of the resultant trees.
 */
object DagMinimalSpanningForest {

  case class Node(values: mutable.Map[Int, Node] = mutable.Map())
  def apply(indexGraphEdges: Array[Array[Int]],
            importantVertices: Set[Int]) = {

    val downstreamGraphEdges = indexGraphEdges
      .zipWithIndex
      .flatMap { case (vs, k) => vs.map((_, k)) }
      .groupMap(_._1)(_._2)

    val rootChangedNodeIndices = importantVertices.filter(i =>
      !indexGraphEdges(i).exists(importantVertices.contains(_))
    )

    val mapping = mutable.Map.empty[Int, Node]

    val output = Node(
      rootChangedNodeIndices
        .map { i =>
          val obj = Node()
          mapping(i) = obj
          (i, obj)
        }
        .to(mutable.Map)
    )

    ResolvedCalls.breadthFirst(rootChangedNodeIndices) { index =>
      val nextIndices = downstreamGraphEdges.getOrElse(index, Array())
      for (nextIndex <- nextIndices) {
        val node = Node()
        mapping(nextIndex) = node
        mapping(index).values(nextIndex) = node
        mapping(nextIndex) = node
      }
      nextIndices
    }
    output
  }
}
