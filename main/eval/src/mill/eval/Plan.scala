package mill.eval
import mill.api.Loose.Agg
import mill.define.{NamedTask, Task}
import mill.util.MultiBiMap

private[mill] class Plan(
    val transitive: Agg[Task[?]],
    val sortedGroups: MultiBiMap[Task[_], Task[_]]
)
private[mill] object Plan {
  def plan(goals: Agg[Task[_]]): Plan = {
    val transitive = Plan.transitiveTargets(goals)
    val topoSorted = Plan.topoSorted(transitive)

    val sortedGroups: MultiBiMap[Task[_], Task[_]] =
      Plan.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] => t
        case t if goals.contains(t) => t
      }

    new Plan(transitive, sortedGroups)
  }

  /**
   * The `values` [[Agg]] is guaranteed to be topological sorted and cycle free.
   * That's why the constructor is package private.
   *
   * @see [[Plan.topoSorted]]
   */
  class TopoSorted(val values: Agg[Task[_]])

  def groupAroundImportantTargets[T](topoSortedTargets: TopoSorted)(important: PartialFunction[
    Task[_],
    T
  ]): MultiBiMap[T, Task[_]] = {

    val output = new MultiBiMap.Mutable[T, Task[_]]()
    for (
      (target, t) <- topoSortedTargets.values.flatMap(t => important.lift(t).map((t, _))).iterator
    ) {

      val transitiveTargets = new Agg.Mutable[Task[_]]
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

  /**
   * Collects all transitive dependencies (targets) of the given targets,
   * including the given targets.
   */
  def transitiveTargets(sourceTargets: Agg[Task[_]]): Agg[Task[_]] = {
    transitiveNodes(sourceTargets)(_.inputs)
  }
  def transitiveNamed(sourceTargets: Agg[Task[_]]): Agg[NamedTask[_]] = {
    transitiveTargets(sourceTargets).collect { case t: NamedTask[?] => t }
  }

  /**
   * Collects all transitive dependencies (nodes) of the given nodes,
   * including the given nodes.
   */
  def transitiveNodes[T](sourceNodes: Agg[T])(inputsFor: T => Seq[T]): Agg[T] = {
    val transitiveNodes = new Agg.Mutable[T]
    def rec(t: T): Unit = {
      if (transitiveNodes.contains(t)) {} // do nothing
      else {
        transitiveNodes.append(t)
        inputsFor(t).foreach(rec)
      }
    }

    sourceNodes.items.foreach(rec)
    transitiveNodes
  }

  /**
   * Takes the given targets, finds all the targets they transitively depend
   * on, and sort them topologically. Fails if there are dependency cycles
   */
  def topoSorted(transitiveTargets: Agg[Task[_]]): TopoSorted = {

    val indexed = transitiveTargets.indexed
    val targetIndices = indexed.zipWithIndex.toMap

    val numberedEdges =
      for (t <- transitiveTargets.items)
        yield t.inputs.collect(targetIndices).toArray

    val sortedClusters = mill.util.Tarjans(numberedEdges.toArray)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(Agg.from(sortedClusters.flatten.map(indexed)))
  }
}
