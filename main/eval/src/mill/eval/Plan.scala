package mill.eval
import mill.api.Loose.Agg
import mill.api.Strict
import mill.define.{NamedTask, Segment, Segments, Task}
import mill.util.MultiBiMap

private object Plan {
  def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val seen = collection.mutable.Set.empty[Segments]
    val overridden = collection.mutable.Set.empty[Task[_]]
    topoSorted.values.reverse.iterator.foreach {
      case x: NamedTask[_] if x.isPrivate == Some(true) =>
        // we always need to store them in the super-path
        overridden.add(x)
      case x: NamedTask[_] =>
        if (!seen.contains(x.ctx.segments)) seen.add(x.ctx.segments)
        else overridden.add(x)
      case _ => // donothing
    }

    val sortedGroups: MultiBiMap[Terminal, Task[_]] =
      Graph.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] =>
          val segments = t.ctx.segments
          val augmentedSegments =
            if (!overridden(t)) segments
            else {
              val Segment.Label(tName) = segments.value.last
              Segments(
                segments.value.init ++
                  Seq(Segment.Label(tName + ".super")) ++
                  t.ctx.enclosing.split("[.# ]").map(Segment.Label)
              )
            }
          Terminal.Labelled(t, augmentedSegments)

        case t if goals.contains(t) => Terminal.Task(t)
      }

    (sortedGroups, transitive)
  }
}
