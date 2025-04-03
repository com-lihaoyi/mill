package mill.define

sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(vs) => vs
    case Segment.Super(original, target) =>
      Seq(s"${original.value}.super${target.fold("")("." + _)}")
    case Segment.SuperRef(_) => sys.error("Unexpected SuperRef in pathSegments")
  }
}

object Segment {
  import scala.math.Ordering.Implicits.seqOrdering
  implicit def ordering: Ordering[Segment] = Ordering.by {
    case Label(value) => (0, value, Nil)
    case Cross(value) => (1, "", value)
    case SuperRef(target) => (2, ".superref", target.toSeq)
    case Super(original, target) => (3, s"${original.value}.super", target.toSeq)
  }
  final case class Label(value: String) extends Segment
  final case class Cross(value: Seq[String]) extends Segment
  final case class Super(original: Label, target: Option[String] = None) extends Segment
  final case class SuperRef(target: Option[String] = None) extends Segment
}
