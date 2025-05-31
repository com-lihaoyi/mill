package mill.resolve

import mill.api.Result
import fastparse.NoWhitespace.noWhitespaceImplicit
import fastparse._
import mill.define.{Segment, Segments, SelectMode}

import scala.annotation.tailrec

private[mill] object ParseArgs {
  def extractSegments(selectorString: String)
      : Result[(Option[Segments], Option[Segments])] =
    parse(selectorString, selector(using _)) match {
      case f: Parsed.Failure => Result.Failure(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Result.Success(selector)
    }

  private def selector[_p: P]: P[(Option[Segments], Option[Segments])] = {
    def wildcard = P("__" | "_")
    def label = P(CharsWhileIn("a-zA-Z0-9_\\-")).!

    def typeQualifier(simple: Boolean) = {
      val maxSegments = if (simple) 0 else Int.MaxValue
      P(("^" | "!").? ~~ label ~~ ("." ~~ label).rep(max = maxSegments)).!
    }

    def typePattern(simple: Boolean) = P(wildcard ~~ (":" ~~ typeQualifier(simple)).rep(1)).!

    def segment0(simple: Boolean) = P(typePattern(simple) | label).map(Segment.Label(_))
    def segment = P("(" ~ segment0(false) ~ ")" | segment0(true))

    def identCross = P(CharsWhileIn("a-zA-Z0-9_\\-.")).!
    def crossSegment = P("[" ~ identCross.rep(1, sep = ",") ~ "]").map(Segment.Cross(_))
    def defaultCrossSegment = P("[]").map(_ => Segment.Cross(Seq()))

    def simpleQuery = P(
      (segment | crossSegment | defaultCrossSegment) ~ ("." ~ segment | crossSegment | defaultCrossSegment).rep
    ).map {
      case (h, rest) => Segments(h +: rest)
    }

    P(simpleQuery ~ ("/" ~ simpleQuery.?).? ~ End).map {
      case (q, None) => (None, Some(q))
      case (q, Some(q2)) => (Some(q), q2)
    }
  }
}
