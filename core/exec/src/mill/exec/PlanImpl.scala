package mill.exec

import mill.api.{Plan, Task}
import mill.api.MultiBiMap
import mill.api.TopoSorted

object PlanImpl {
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
    val topoSortedIndices = topoSortedTasks.values.zipWithIndex.toMap
    for (task <- topoSortedTasks.values) {
      for (t <- important.lift(task)) {

        val transitiveTasks = collection.mutable.Map[Task[?], Int]()

        def rec(t: Task[?]): Unit = {
          if (transitiveTasks.contains(t)) () // do nothing
          else if (important.isDefinedAt(t) && t != task) () // do nothing
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
   * Returns the named tasks reachable from `t` by walking through anonymous
   * tasks (Task.Anon, Task.Mapped, Task.Sequence, ...) until the first named
   * task is found on each path. Cuts at the first named task: does not
   * recurse into its named upstream chain. Uses a local visited set to handle
   * shared anonymous subgraphs without duplicate work.
   */
  def immediateNamedUpstreams(t: Task[?]): Seq[Task.Named[?]] = {
    val out = collection.mutable.LinkedHashSet.empty[Task.Named[?]]
    val seen = collection.mutable.HashSet.empty[Task[?]]
    def rec(node: Task[?]): Unit = {
      if (seen.add(node)) {
        node.inputs.foreach {
          case n: Task.Named[?] => out += n
          case other => rec(other)
        }
      }
    }
    rec(t)
    out.toSeq
  }

  /**
   * Height of a named task in its intrinsic upstream graph: 0 if it has no
   * named upstreams, else 1 + max height of its immediate named upstreams.
   * Computed from `t.inputs` walked through anonymous tasks, so it depends
   * only on the task's own definition. This is the property the cross-launcher
   * lock-order gate relies on: two launchers planning different goal subsets
   * compute identical heights for shared named tasks.
   */
  def namedUpstreamHeight(
      t: Task.Named[?],
      memo: collection.mutable.Map[Task.Named[?], Int]
  ): Int = memo.getOrElseUpdate(
    t, {
      val parents = immediateNamedUpstreams(t)
      if (parents.isEmpty) 0
      else 1 + parents.iterator.map(namedUpstreamHeight(_, memo)).max
    }
  )

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
