package mill.eval
import mill.api.Loose.Agg
import mill.api.Strict
import mill.define.{NamedTask, Segments, Task}
import mill.util.MultiBiMap

private[mill] object Plan {
  def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val overridden = collection.mutable.Map.empty[Segments, Seq[String]]
    topoSorted.values.reverse.iterator.foreach {
      case t: NamedTask[_] =>
        val segments = t.ctx.segments
        // we always store private tasks in the super-path to avoid collisions with
        // subclass implementations with the same name
        if (t.isPrivate == Some(true) || overridden.contains(segments)) {
          overridden.updateWith(segments)(o => Some(o.getOrElse(Nil) ++ Seq(t.ctx.enclosing)))
        } else {
          overridden.updateWith(segments)(o => Some(o.getOrElse(Nil)))
        }

      case _ => // do nothing
    }

    val sortedGroups: MultiBiMap[Terminal, Task[_]] =
      Graph.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] => Terminal.Labelled(t, t.ctx.segments)
        case t if goals.contains(t) => Terminal.Task(t)
      }

    (sortedGroups, transitive)
  }
}
