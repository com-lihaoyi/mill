package mill.exec

import mill.define.{NamedTask, Task, Plan}
import mill.define.MultiBiMap
import mill.define.internal.TopoSorted

private[mill] object PlanImpl {
  def plan(goals: Seq[Task[?]]): Plan = {
    val transitive = PlanImpl.transitiveTargets(goals.toIndexedSeq)
    val goalSet = goals.toSet
    val topoSorted = PlanImpl.topoSorted(transitive)

    val sortedGroups: MultiBiMap[Task[?], Task[?]] =
      PlanImpl.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] => t
        case t if goalSet.contains(t) => t
      }

    new Plan(transitive, sortedGroups)
  }

  /**
   * The `values` [[Agg]] is guaranteed to be topological sorted and cycle free.
   * That's why the constructor is package private.
   *
   * @see [[PlanImpl.topoSorted]]
   */

  def groupAroundImportantTargets[T](topoSortedTargets: TopoSorted)(important: PartialFunction[
    Task[?],
    T
  ]): MultiBiMap[T, Task[?]] = {

    val output = new MultiBiMap.Mutable[T, Task[?]]()
    for (
      (target, t) <- topoSortedTargets.values.flatMap(t => important.lift(t).map((t, _))).iterator
    ) {

      val transitiveTargets = collection.mutable.LinkedHashSet[Task[?]]()
      def rec(t: Task[?]): Unit = {
        if (transitiveTargets.contains(t)) () // do nothing
        else if (important.isDefinedAt(t) && t != target) () // do nothing
        else {
          transitiveTargets.add(t)
          t.inputs.foreach(rec)
        }
      }
      rec(target)
      output.addAll(t, topoSorted(transitiveTargets.toIndexedSeq).values)
    }
    output
  }

  /**
   * Collects all transitive dependencies (targets) of the given targets,
   * including the given targets.
   */
  def transitiveTargets(sourceTargets: Seq[Task[?]]): IndexedSeq[Task[?]] = {
    transitiveNodes(sourceTargets)(_.inputs)
  }
  def transitiveNamed(sourceTargets: Seq[Task[?]]): Seq[NamedTask[?]] = {
    transitiveTargets(sourceTargets).collect { case t: NamedTask[?] => t }
  }

  /**
   * Collects all transitive dependencies (nodes) of the given nodes,
   * including the given nodes.
   */
  def transitiveNodes[T](sourceNodes: Seq[T])(inputsFor: T => Seq[T]): IndexedSeq[T] = {
    val transitiveNodes = collection.mutable.LinkedHashSet[T]()
    def rec(t: T): Unit = {
      if (transitiveNodes.contains(t)) {} // do nothing
      else {
        transitiveNodes.add(t)
        inputsFor(t).foreach(rec)
      }
    }

    sourceNodes.foreach(rec)
    transitiveNodes.toIndexedSeq
  }

  /**
   * Takes the given targets, finds all the targets they transitively depend
   * on, and sort them topologically. Fails if there are dependency cycles
   */
  def topoSorted(transitiveTargets: IndexedSeq[Task[?]]): TopoSorted = {

    val indexed = transitiveTargets
    val targetIndices = indexed.zipWithIndex.toMap

    val numberedEdges =
      for (t <- transitiveTargets)
        yield t.inputs.collect(targetIndices).toArray

    val sortedClusters = mill.internal.Tarjans(numberedEdges.toArray)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(IndexedSeq.from(sortedClusters.flatten.map(indexed)))
  }
}
