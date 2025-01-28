package mill.eval
import mill.api.Loose.Agg
import mill.api.Strict
import mill.define.{NamedTask, Segment, Segments, Task}
import mill.util.MultiBiMap

import java.util.StringTokenizer
import scala.jdk.CollectionConverters.IteratorHasAsScala

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
        case t: NamedTask[Any] =>
          val segments = t.ctx.segments
          val augmentedSegments =
            if (!overridden(segments).contains(t.ctx.enclosing)) segments
            else assignOverridenTaskSegments(overridden(segments), t)
          Terminal.Labelled(t, augmentedSegments)

        case t if goals.contains(t) => Terminal.Task(t)
      }

    (sortedGroups, transitive)
  }

  /**
   * If a task has been overridden, give it a name by looking at all the
   * other overridden tasks of the same name, and finding the shortest
   * suffix that uniquely distinguishes them.
   */
  private def assignOverridenTaskSegments(overriddenEnclosings: Seq[String], t: NamedTask[Any]) = {
    // StringTokenizer is faster than String#split due to not using regexes
    def splitEnclosing(s: String) = new StringTokenizer(s, ".# ")
      .asIterator()
      .asScala.map(_.asInstanceOf[String])
      .filter(_ != "<empty>")
      .toArray
    val segments = t.ctx.segments
    val superSegmentStrings = overriddenEnclosings.map(splitEnclosing)

    // Find out how many segments of the enclosing strings are identical
    // among all overridden tasks, so we can drop them
    val shortestSuperLength = superSegmentStrings.map(_.length).min
    val dropLeft = Range(0, shortestSuperLength)
      .find(i => superSegmentStrings.distinctBy(_(i)).size != 1)
      .getOrElse(shortestSuperLength)

    val splitted = splitEnclosing(t.ctx.enclosing)
    // `dropRight(1)` to always drop the task name, which has to be
    // the same for all overridden tasks with the same segments
    val superSuffix0 = splitted.drop(dropLeft).dropRight(1)

    // If there are no different segments between the enclosing strings,
    // preserve at least one path segment which is the class name
    val superSuffix =
      if (superSuffix0.nonEmpty) superSuffix0.toSeq
      else Seq(splitted(splitted.length - 2))

    val Segment.Label(tName) = segments.value.last
    Segments(
      segments.value.init ++
        Seq(Segment.Label(tName + ".super")) ++ superSuffix.map(Segment.Label)
    )
  }
}
