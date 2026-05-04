package mill.exec

import mill.api.{Plan, Task}
import mill.api.MultiBiMap
import mill.api.TopoSorted

object PlanImpl {
  def plan(goals: Seq[Task[?]]): Plan = {
    val transitive = PlanImpl.transitiveTasks(goals.toIndexedSeq)
    val goalSet = goals.toSet
    val topoSorted = PlanImpl.topoSorted(transitive)

    // Anonymous goals are group terminals (so the user gets a result) but are
    // *not* cut points: traversing through them keeps `interGroupDeps` a
    // function of the named graph alone, so heights for shared named tasks
    // agree across launchers regardless of which anon tasks any peer launcher
    // happens to pass as goals.
    val sortedGroups: MultiBiMap[Task[?], Task[?]] =
      PlanImpl.groupAroundImportantTasks(topoSorted, _.isInstanceOf[Task.Named[?]]) {
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
  ]): MultiBiMap[T, Task[?]] =
    groupAroundImportantTasks(topoSortedTasks, important.isDefinedAt)(important)

  /**
   * `cutPoint` decides where group traversal stops; `important` decides which
   * tasks become group terminals in the outer iteration. They differ when an
   * anonymous task can be a goal (group terminal) without acting as a cut
   * point for other groups walking through it.
   *
   * Consequence for anonymous goals: an anon `y` reachable from a named
   * group's terminal walks into both groups, so its body is evaluated once
   * per containing group. This matches Mill's existing anon-shared-across-
   * groups model (see `multiTerminalGroup`) and assumes anonymous tasks are
   * pure. If side effects are placed in a `Task.Anon` and that task is also
   * passed as a goal, it will run twice per launcher.
   */
  def groupAroundImportantTasks[T](
      topoSortedTasks: TopoSorted,
      cutPoint: Task[?] => Boolean
  )(important: PartialFunction[Task[?], T]): MultiBiMap[T, Task[?]] = {

    val output = new MultiBiMap.Mutable[T, Task[?]]()
    val topoSortedIndices = topoSortedTasks.values.zipWithIndex.toMap
    for (task <- topoSortedTasks.values) {
      for (t <- important.lift(task)) {

        val transitiveTasks = collection.mutable.Map[Task[?], Int]()

        def rec(t: Task[?]): Unit = {
          if (transitiveTasks.contains(t)) () // do nothing
          else if (cutPoint(t) && t != task) () // do nothing
          else {
            transitiveTasks.put(t, topoSortedIndices(t))
            t.inputs.foreach(rec)
          }
        }

        rec(task)
        val out = transitiveTasks.toArray
        out.sortInPlaceBy(_._2)
        output.addAll(t, out.map(_._1))
      }
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

    val numberedEdges = transitiveTasks.map(_.inputs.collect(taskIndices).toArray)

    val sortedClusters = mill.internal.Tarjans(numberedEdges)
    assert(sortedClusters.count(_.length > 1) == 0, sortedClusters.filter(_.length > 1))
    new TopoSorted(sortedClusters.map(_(0)).map(indexed))
  }
}
