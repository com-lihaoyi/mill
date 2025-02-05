package mill.eval
import mill.api.Loose.Agg
import mill.api.Strict
import mill.define.{NamedTask, Task}
import mill.util.MultiBiMap

private[mill] class Plan(
    val transitive: Agg[Task[?]],
    val sortedGroups: MultiBiMap[Terminal, Task[_]]
)
private[mill] object Plan {
  def plan(goals: Agg[Task[_]]): Plan = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)

    val sortedGroups: MultiBiMap[Terminal, Task[_]] =
      Graph.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] => Terminal.Labelled(t)
        case t if goals.contains(t) => Terminal.Task(t)
      }

    new Plan(transitive, sortedGroups)
  }
}
