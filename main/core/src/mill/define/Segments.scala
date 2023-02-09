package mill.define

/**
 * Models a path with the Mill build hierarchy, e.g. `amm.util[2.11].test.compile`.
 * Segments must start with a [[Segment.Label]].
 *
 * `.`-separated segments are [[Segment.Label]]s,
 * while `[]`-delimited segments are [[Segment.Cross]]s
 */
case class Segments private (value: Segment*) {

  def ++(other: Seq[Segment]): Segments = Segments(value ++ other: _*)
  def ++(other: Segments): Segments = Segments(value ++ other.value: _*)

  def parts: List[String] = value.toList match {
    case Nil => Nil
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.flatMap {
        case Segment.Label(s) => Seq(s)
        case Segment.Cross(vs) => vs.map(_.toString)
      }
      head +: stringSegments
    case Segment.Cross(_) :: _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }

  def head: Segment.Label = value.head match {
    case l: Segment.Label => l
    case _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }

  def render: String = value.toList match {
    case Nil => ""
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
      }
      head + stringSegments.mkString
    case Segment.Cross(_) :: _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }
}

object Segments {
  def apply(): Segments = new Segments()
  def apply(head: Segment.Label, tail: Segment*): Segments = new Segments(head +: tail: _*)

  def labels(values: String*): Segments =
    Segments(values.map(Segment.Label): _*)

}
