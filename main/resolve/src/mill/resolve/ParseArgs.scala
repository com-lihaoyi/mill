package mill.resolve

import fastparse.NoWhitespace.noWhitespaceImplicit
import fastparse._
import mill.define.{Segment, Segments}
import mill.util.EitherOps

import scala.annotation.tailrec

object ParseArgs {

  type TargetsWithParams = (Seq[(Option[Segments], Option[Segments])], Seq[String])

  /** Separator used in multiSelect-mode to separate targets from their args. */
  val MultiArgsSeparator = "--"

  /** Separator used in [[SelectMode.Separated]] mode to separate a target-args-tuple from the next target. */
  val TargetSeparator = "+"

  def apply(
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, Seq[TargetsWithParams]] = {

    val MaskPattern = ("""\\+\Q""" + TargetSeparator + """\E""").r

    /**
     * Partition the arguments in groups using a separator.
     * To also use the separator as argument, masking it with a backslash (`\`) is supported.
     */
    @tailrec
    def separated(result: Seq[Seq[String]], rest: Seq[String]): Seq[Seq[String]] = rest match {
      case Seq() => if (result.nonEmpty) result else Seq(Seq())
      case r =>
        val (next, r2) = r.span(_ != TargetSeparator)
        separated(
          result ++ Seq(next.map {
            case x @ MaskPattern(_*) => x.drop(1)
            case x => x
          }),
          r2.drop(1)
        )
    }
    val parts: Seq[Seq[String]] = separated(Seq() /* start value */, scriptArgs)
    val parsed: Seq[Either[String, TargetsWithParams]] =
      parts.map(extractAndValidate(_, selectMode == SelectMode.Multi))

    val res1: Either[String, Seq[TargetsWithParams]] = EitherOps.sequence(parsed)

    res1
  }

  private def extractAndValidate(
      scriptArgs: Seq[String],
      multiSelect: Boolean
  ): Either[String, TargetsWithParams] = {
    val (selectors, args) = extractSelsAndArgs(scriptArgs, multiSelect)
    for {
      _ <- validateSelectors(selectors)
      expandedSelectors <- EitherOps
        .sequence(selectors.map(ExpandBraces.expandBraces))
        .map(_.flatten)
      selectors <- EitherOps.sequence(expandedSelectors.map(extractSegments))
    } yield (selectors.toList, args)
  }

  def extractSelsAndArgs(
      scriptArgs: Seq[String],
      multiSelect: Boolean
  ): (Seq[String], Seq[String]) = {

    if (multiSelect) {
      val dd = scriptArgs.indexOf(MultiArgsSeparator)
      val selectors = if (dd == -1) scriptArgs else scriptArgs.take(dd)
      val args = if (dd == -1) Seq.empty else scriptArgs.drop(dd + 1)

      (selectors, args)
    } else {
      (scriptArgs.take(1), scriptArgs.drop(1))
    }
  }

  private def validateSelectors(selectors: Seq[String]): Either[String, Unit] = {
    if (selectors.isEmpty || selectors.exists(_.isEmpty))
      Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
    else Right(())
  }

  def extractSegments(selectorString: String)
      : Either[String, (Option[Segments], Option[Segments])] =
    parse(selectorString, selector(using _)) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }

  private def selector[_p: P]: P[(Option[Segments], Option[Segments])] = {
    def wildcard = P("__" | "_")
    def label = mill.define.Reflect.ident

    def typeQualifier(simple: Boolean) = {
      val maxSegments = if (simple) 0 else Int.MaxValue
      P(("^" | "!").? ~~ label ~~ ("." ~~ label).rep(max = maxSegments)).!
    }

    def typePattern(simple: Boolean) = P(wildcard ~~ (":" ~~ typeQualifier(simple)).rep(1)).!

    def segment0(simple: Boolean) = P(typePattern(simple) | label).map(Segment.Label)
    def segment = P("(" ~ segment0(false) ~ ")" | segment0(true))

    def identCross = P(CharsWhileIn("a-zA-Z0-9_\\-.")).!
    def crossSegment = P("[" ~ identCross.rep(1, sep = ",") ~ "]").map(Segment.Cross)
    def defaultCrossSegment = P("[]").map(_ => Segment.Cross(Seq()))

    def simpleQuery = P(segment ~ ("." ~ segment | crossSegment | defaultCrossSegment).rep).map {
      case (h, rest) => Segments(h +: rest)
    }

    P(simpleQuery ~ ("/" ~ simpleQuery.?).? ~ End).map {
      case (q, None) => (None, Some(q))
      case (q, Some(q2)) => (Some(q), q2)
    }
  }
}
