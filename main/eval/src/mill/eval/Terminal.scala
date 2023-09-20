package mill.eval

import mill.define.{NamedTask, Segment, Segments}

/**
 * A terminal or terminal target is some important work unit, that in most cases has a name (Right[Labelled])
 * or was directly called by the user (Left[Task]).
 * It's a T, T.worker, T.input, T.source, T.sources, T.persistent
 */
sealed trait Terminal {
  def render: String
}

object Terminal {
  case class Labelled[T](task: NamedTask[T], segments: Segments) extends Terminal {
    def render = segments.render
  }

  case class Task[T](task: mill.define.Task[_]) extends Terminal {
    def render = task.toString
  }

  def destSegments(labelledTask: Terminal.Labelled[_]): Segments = {
    labelledTask.task.ctx.foreign match {
      case Some(foreignSegments) => foreignSegments ++ labelledTask.segments
      case None => labelledTask.segments
    }
  }

  def printTerm(term: Terminal): String = term match {
    case Terminal.Task(task) => task.toString()
    case labelled: Terminal.Labelled[_] =>
      val Seq(first, rest @ _*) = destSegments(labelled).value
      val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(s) => "[" + s.mkString(",") + "]"
      }
      msgParts.mkString
  }
}
