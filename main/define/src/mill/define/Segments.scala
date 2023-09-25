package mill.define

/**
 * Models a path with the Mill build hierarchy, e.g. `amm.util[2.11].test.compile`.
 * Segments must start with a [[Segment.Label]].
 *
 * `.`-separated segments are [[Segment.Label]]s,
 * while `[]`-delimited segments are [[Segment.Cross]]s
 */
case class Segments private (value: Seq[Segment]) {

  def init = Segments(value.init)
  def ++(other: Segment): Segments = Segments(value ++ Seq(other))
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other)
  def ++(other: Segments): Segments = Segments(value ++ other.value)

  def parts: List[String] = value.toList match {
    case Nil => Nil
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.flatMap {
        case Segment.Label(s) => Seq(s)
        case Segment.Cross(vs) => vs
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
  def apply(): Segments = new Segments(Nil)
  def apply(items: Seq[Segment]): Segments = new Segments(items)
  def labels(values: String*): Segments = Segments(values.map(Segment.Label))

  def checkPatternMatch(pattern0: Segments, input0: Segments): Boolean = {
    def rec(pattern: List[Segment], input: List[Segment]): Boolean = {
      import Segment.{Label, Cross}
      (pattern, input) match {
        case (Nil, Nil) => true
        case (Label("__") :: pRest, iRest) => iRest.tails.exists(rec(pRest, _))
        case (Label("_") :: pRest, _ :: iRest) => rec(pRest, iRest)

        case (Label(a) :: pRest, Label(b) :: iRest) if a == b => rec(pRest, iRest)

        case (Cross(as) :: pRest, Cross(bs) :: iRest) =>
          val crossMatches = as.zip(bs).forall {
            case ("_", _) => true
            case (a, b) => a == b
          }

          if (crossMatches) rec(pRest, iRest)
          else false

        case (_, _) => false
      }
    }

    rec(pattern0.value.toList, input0.value.toList)
  }
}
