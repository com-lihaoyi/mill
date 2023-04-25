package mill.define

sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(vs) => vs
  }

  def render: String
}

object Segment {
  final case class Label(value: String) extends Segment {
    def render = s".$value"
  }
  final case class Cross(value: Seq[String]) extends Segment {
    def render = "[" + value.mkString(",") + "]"
  }
}
