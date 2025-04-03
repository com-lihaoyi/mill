package mill.define

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
          case Segment.Super(original, targetOpt) =>
            original.value + ".super" + targetOpt.fold("")("." + _)
        val stringSegments = rest.map {
          case Segment.Label(s) => "." + s
          case c: Segment.Cross => renderCross(c)
          case Segment.Super(original, targetOpt) =>
            ".super" + targetOpt.fold("")("." + _)
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

  /**
   * Determines if segments represent a super task by examining the last segment
   */
  def isSuperTask(segments: Segments): Boolean = {
    segments.value.lastOption.exists(_.isInstanceOf[Segment.Super])
  }

  /**
   * Extracts base task information from a super task's segments
   * @param segments The segments potentially containing a super task
   * @return A tuple containing (base task name, base task segments)
   */
  def extractBaseTaskInfo(segments: Segments): (String, Segments) = {
    segments.value.lastOption match {
      case Some(Segment.Super(original, _)) =>
        (original.value, Segments(segments.value.init :+ original))
      case Some(Segment.Label(value)) => (value, segments)
      case _ => ("", segments)
    }
  }

  def extractBaseTaskName(segments: Segments): String = extractBaseTaskInfo(segments)._1
  def extractBaseTask(segments: Segments): Segments = extractBaseTaskInfo(segments)._2

  /**
   * Creates segments for a super task, ensuring proper structure
   * @param base The base segments
   * @param superTarget Optional super target trait/class name
   */
  def superTask(base: Segments, superTarget: Option[String] = None): Segments = {
    base.value.lastOption match {
      case Some(label: Segment.Label) =>
        val newLastSegment = Segment.Super(label, superTarget)
        Segments(base.value.init :+ newLastSegment)
      case Some(other) =>
        throw new IllegalArgumentException(
          s"Cannot apply .super modifier to non-Label segment: ${base.render} / $other"
        )
      case None =>
        throw new IllegalArgumentException("Cannot apply .super modifier to empty Segments")
    }
  }
}
