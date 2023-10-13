package mill.eval
import mill.api.Loose.Agg
import mill.define.{NamedTask, Segment, Segments, Task}
import mill.util.MultiBiMap

/**
 * Execution plan
 * @param sortedGroups
 * @param transitive
 */
private[mill] case class Plan private (
    sortedGroups: MultiBiMap[Terminal, Task[_]],
    transitive: Agg[Task[_]]
) {
  lazy val terminals: Vector[Terminal] = sortedGroups.keys().toVector
  lazy val interGroupDeps: Map[Terminal, Seq[Terminal]] = Plan.findInterGroupDeps(sortedGroups)
}

private object Plan {

  def plan(goals: Agg[Task[_]]): Plan = {
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

    Plan(sortedGroups, transitive)
  }

  private def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]])
      : Map[Terminal, Seq[Terminal]] = {
    sortedGroups
      .items()
      .map { case (terminal, group) =>
        terminal -> Seq.from(group)
          .flatMap(_.inputs)
          .filterNot(group.contains)
          .distinct
          .map(sortedGroups.lookupValue)
          .distinct
      }
      .toMap
  }

}
