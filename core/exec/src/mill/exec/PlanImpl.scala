package mill.exec

import mill.api.{Task, Plan}
import mill.api.MultiBiMap
import mill.api.TopoSorted

private[mill] object PlanImpl {
  def plan(goals: Seq[Task[?]]): Plan = {
    val transitive = PlanImpl.transitiveTasks(goals.toIndexedSeq)
    val goalSet = goals.toSet
    val topoSorted = PlanImpl.topoSorted(transitive)

    val sortedGroups: MultiBiMap[Task[?], Task[?]] =
      PlanImpl.groupAroundImportantTasks(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: Task.Named[Any] => t
        case t if goalSet.contains(t) => t
      }

    new Plan(sortedGroups)
  }

  /**
   * The `values` [[Agg]] is guaranteed to be topological sorted and cycle free.
   * That's why the constructor is package private.
   *
   * @see [[PlanImpl.topoSorted]]
   */

  def groupAroundImportantTasks[T](topoSortedTasks: TopoSorted)(important: PartialFunction[
    Task[?],
    T
  ]): MultiBiMap[T, Task[?]] = {

    val output = new MultiBiMap.Mutable[T, Task[?]]()
    for ((task, t) <- topoSortedTasks.values.flatMap(t => important.lift(t).map((t, _))).iterator) {

      val transitiveTasks = collection.mutable.LinkedHashSet[Task[?]]()
      def rec(t: Task[?]): Unit = {
        if (transitiveTasks.contains(t)) () // do nothing
        else if (important.isDefinedAt(t) && t != task) () // do nothing
        else {
          transitiveTasks.add(t)
          t.inputs.foreach(rec)
        }
      }
      rec(task)
      output.addAll(t, topoSorted(transitiveTasks.toIndexedSeq).values)
    }
    output
  }

  /**
   * Collects all transitive dependencies (tasks) of the given tasks,
   * including the given tasks.
   */
  def transitiveTasks(sourceTasks: Seq[Task[?]]): IndexedSeq[Task[?]] = {
    transitiveNodes(sourceTasks)(_.inputs)
  }
  def transitiveNamed(sourceTasks: Seq[Task[?]]): Seq[Task.Named[?]] = {
    transitiveTasks(sourceTasks).collect { case t: Task.Named[?] => t }
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
   * Takes the given tasks, finds all the targets they transitively depend
   * on, and sort them topologically. Fails if there are dependency cycles
   */
  def topoSorted(transitiveTasks: IndexedSeq[Task[?]]): TopoSorted = {

    val indexed = transitiveTasks
    val taskIndices = indexed.zipWithIndex.toMap

    val numberedEdges =
      for (t <- transitiveTasks)
        yield t.inputs.collect(taskIndices).toArray

    val sortedClusters = mill.internal.Tarjans(numberedEdges.toArray)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(IndexedSeq.from(sortedClusters.flatten.map(indexed)))
  }
}
