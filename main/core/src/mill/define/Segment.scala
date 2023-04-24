package mill.define

sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(vs) => vs.map(_.toString)
  }
}

object Segment {
  final case class Label(value: String) extends Segment
  final case class Cross(value: Seq[String]) extends Segment
}
