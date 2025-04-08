package mill.runner.api
sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(vs) => vs
  }
}

object Segment {
  import scala.math.Ordering.Implicits.seqOrdering
  implicit def ordering: Ordering[Segment] = Ordering.by {
    case Label(value) => (value, Nil)
    case Cross(value) => ("", value)
  }
  final case class Label(value: String) extends Segment
  final case class Cross(value: Seq[String]) extends Segment
}
