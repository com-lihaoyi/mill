package mill.define

import mill.eval.Tarjans
import mill.util.{MultiBiMap, OSet}

object Graph {
  class TopoSorted private[Graph](val values: OSet[Task[_]])
  def groupAroundImportantTargets[T](topoSortedTargets: TopoSorted)
                                    (important: PartialFunction[Task[_], T]): MultiBiMap[T, Task[_]] = {

    val output = new MultiBiMap.Mutable[T, Task[_]]()
    for ((target, t) <- topoSortedTargets.values.flatMap(t => important.lift(t).map((t, _)))) {

      val transitiveTargets = new OSet.Mutable[Task[_]]
      def rec(t: Task[_]): Unit = {
        if (transitiveTargets.contains(t)) () // do nothing
        else if (important.isDefinedAt(t) && t != target) () // do nothing
        else {
          transitiveTargets.append(t)
          t.inputs.foreach(rec)
        }
      }
      rec(target)
      output.addAll(t, topoSorted(transitiveTargets).values)
    }
    output
  }

  def transitiveTargets(sourceTargets: OSet[Task[_]]): OSet[Task[_]] = {
    val transitiveTargets = new OSet.Mutable[Task[_]]
    def rec(t: Task[_]): Unit = {
      if (transitiveTargets.contains(t)) () // do nothing
      else {
        transitiveTargets.append(t)
        t.inputs.foreach(rec)
      }
    }

    sourceTargets.items.foreach(rec)
    transitiveTargets
  }
  /**
    * Takes the given targets, finds all the targets they transitively depend
    * on, and sort them topologically. Fails if there are dependency cycles
    */
  def topoSorted(transitiveTargets: OSet[Task[_]]): TopoSorted = {

    val indexed = transitiveTargets.indexed
    val targetIndices = indexed.zipWithIndex.toMap

    val numberedEdges =
      for(t <- transitiveTargets.items)
        yield t.inputs.collect(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(OSet.from(sortedClusters.flatten.map(indexed)))
  }
}
