package mill.define

sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(vs) => vs
    case Segment.Super(original, target) =>
      Seq(s"${original.value}.super${target.fold("")("." + _)}")
  }
}

object Segment {
  import scala.math.Ordering.Implicits.seqOrdering
  implicit def ordering: Ordering[Segment] = Ordering.by {
    case Label(value) => (value, Nil)
    case Cross(value) => ("", value)
    case Super(original, target) => (s"${original.value}.super", target.toSeq)
  }
  final case class Label(value: String) extends Segment
  final case class Cross(value: Seq[String]) extends Segment
  final case class Super(original: Label, target: Option[String] = None) extends Segment
}
