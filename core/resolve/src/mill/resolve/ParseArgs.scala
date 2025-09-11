package mill.resolve

import mill.api.Result
import fastparse.NoWhitespace.noWhitespaceImplicit
import fastparse._
import mill.api.{Segment, Segments, SelectMode}

import scala.annotation.tailrec

private[mill] object ParseArgs {

  type TasksWithParams = (Seq[(Option[Segments], Option[Segments])], Seq[String])

  /** Separator used in multiSelect-mode to separate tasks from their args. */
  val MultiArgsSeparator = "--"

  /** Separator used in [[SelectMode.Separated]] mode to separate a task-args-tuple from the next target. */
  val TaskSeparator = "+"

  def apply(scriptArgs: Seq[String], selectMode: SelectMode): Result[Seq[TasksWithParams]] = {

    val MaskPattern = ("""\\+\Q""" + TaskSeparator + """\E""").r

    /**
     * Partition the arguments in groups using a separator.
     * To also use the separator as argument, masking it with a backslash (`\`) is supported.
     */
    @tailrec
    def separated(result: Seq[Seq[String]], rest: Seq[String]): Seq[Seq[String]] = rest match {
      case Seq() => if (result.nonEmpty) result else Seq(Seq())
      case r =>
        val (next, r2) = r.span(_ != TaskSeparator)
        separated(
          result ++ Seq(next.map {
            case x @ MaskPattern(_*) => x.drop(1)
            case x => x
          }),
          r2.drop(1)
        )
    }
    val parts: Seq[Seq[String]] = separated(Seq() /* start value */, scriptArgs)
    val parsed: Seq[Result[TasksWithParams]] =
      parts.map(extractAndValidate(_, selectMode == SelectMode.Multi))

    val res1: Result[Seq[TasksWithParams]] = Result.sequence(parsed)

    res1
  }

  private def extractAndValidate(
      scriptArgs: Seq[String],
      multiSelect: Boolean
  ): Result[TasksWithParams] = {
    val (selectors, args) = extractSelsAndArgs(scriptArgs, multiSelect)
    for {
      _ <- validateSelectors(selectors)
      expandedSelectors <- Result
        .sequence(selectors.map(ExpandBraces.expandBraces))
        .map(_.flatten)
      selectors <- Result.sequence(expandedSelectors.map(extractSegments))
    } yield (selectors.iterator.toList, args)
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

  private def validateSelectors(selectors: Seq[String]): Result[Unit] = {
    if (selectors.isEmpty || selectors.exists(_.isEmpty)) {
      Result.Failure(
        "Task selector must not be empty. Try `mill resolve _` to see what's available."
      )
    } else Result.Success(())
  }

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
