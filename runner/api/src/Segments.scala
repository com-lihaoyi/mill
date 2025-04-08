package mill.runner.api

import scala.math.Ordering.Implicits.seqOrdering

/**
 * Models a path with the Mill build hierarchy, e.g. `amm.util[2.11].test.compile`.
 *
 * `.`-separated segments are [[Segment.Label]]s,
 * while `[]`-delimited segments are [[Segment.Cross]]s
 */
case class Segments private (value: Seq[Segment]) {

  def init: Segments = Segments(value.init)
  def ++(other: Segment): Segments = Segments(value ++ Seq(other))
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other)
  def ++(other: Segments): Segments = Segments(value ++ other.value)

  def startsWith(prefix: Segments): Boolean =
    value.startsWith(prefix.value)

  def last: Segment.Label = value.last match {
    case l: Segment.Label => l
    case _ =>
      throw new IllegalArgumentException("Segments must end with a Label, but found a Cross.")
  }

  def parts: List[String] = value.flatMap(_.pathSegments).toList

  def head: Segment = value.head

  def render: String = {
    def renderCross(cross: Segment.Cross): String = "[" + cross.value.mkString(",") + "]"
    value.toList match {
      case Nil => ""
      case head :: rest =>
        val headSegment = head match
          case Segment.Label(s) => s
          case c: Segment.Cross => renderCross(c)
        val stringSegments = rest.map {
          case Segment.Label(s) => "." + s
          case c: Segment.Cross => renderCross(c)
        }
        headSegment + stringSegments.mkString
    }
  }
  override lazy val hashCode: Int = value.hashCode()
}

object Segments {
  implicit def ordering: Ordering[Segments] = Ordering.by(_.value)
  def apply(): Segments = new Segments(Nil)
  def apply(items: Seq[Segment]): Segments = new Segments(items)
  def labels(values: String*): Segments = Segments(values.map(Segment.Label(_)))
}
