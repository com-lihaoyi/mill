package mill.define

import scala.annotation.tailrec

import fastparse._
import fastparse.NoWhitespace.noWhitespaceImplicit
import mill.util.EitherOps

sealed trait SelectMode
object SelectMode {

  /** All args are treated as targets or commands. If a `--` is detected, subsequent args are parameters to all commands. */
  object Multi extends SelectMode

  /** Only the first arg is treated as target or command, subsequent args are parameters of the command. */
  object Single extends SelectMode

  /** Like a combination of [[Single]] and [[Multi]], behaving like [[Single]] but using a special separator (`++`) to start parsing another target/command. */
  object Separated extends SelectMode
}

object ParseArgs {

  type TargetsWithParams = (Seq[(Option[Segments], Segments)], Seq[String])

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
    val parts: Seq[Seq[String]] = separated(Seq(), scriptArgs)
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
        .sequence(selectors.map(expandBraces))
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
      Left("Selector cannot be empty")
    else Right(())
  }

  def expandBraces(selectorString: String): Either[String, List[String]] = {
    parseBraceExpansion(selectorString) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(expanded, _) => Right(expanded.toList)
    }
  }

  private sealed trait Fragment
  private object Fragment {
    case class Keep(value: String) extends Fragment
    case class Expand(values: List[List[Fragment]]) extends Fragment

    def unfold(fragments: List[Fragment]): Seq[String] = {
      fragments match {
        case head :: rest =>
          val prefixes = head match {
            case Keep(v) => Seq(v)
            case Expand(Nil) => Seq("{}")
            case Expand(List(vs)) => unfold(vs).map("{" + _ + "}")
            case Expand(vss) => vss.flatMap(unfold)
          }
          for {
            prefix <- prefixes
            suffix <- unfold(rest)
          } yield prefix + suffix

        case Nil => Seq("")
      }
    }
  }

  private object BraceExpansionParser {
    def plainChars[_p: P]: P[Fragment.Keep] =
      P(CharsWhile(c => c != ',' && c != '{' && c != '}')).!.map(Fragment.Keep)

    def toExpand[_p: P]: P[Fragment] =
      P("{" ~ braceParser.rep(1).rep(sep = ",") ~ "}").map(x =>
        Fragment.Expand(x.toList.map(_.toList))
      )

    def braceParser[_p: P]: P[Fragment] = P(toExpand | plainChars)

    def parser[_p: P]: P[Seq[String]] = P(braceParser.rep(1).rep(sep = ",") ~ End).map { vss =>
      def unfold(vss: List[Seq[String]]): Seq[String] = {
        vss match {
          case Nil => Seq("")
          case head :: rest =>
            for {
              str <- head
              r <- unfold(rest)
            } yield r match {
              case "" => str
              case _ => str + "," + r
            }
        }
      }

      val stringss = vss.map(x => Fragment.unfold(x.toList)).toList
      unfold(stringss)
    }
  }

  private def parseBraceExpansion(input: String): Parsed[Seq[String]] = {

    parse(
      input,
      BraceExpansionParser.parser(_)
    )
  }

  def extractSegments(selectorString: String): Either[String, (Option[Segments], Segments)] =
    parseSelector(selectorString) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }

  private def ident[_p: P]: P[String] = P(CharsWhileIn("a-zA-Z0-9_\\-")).!

  def standaloneIdent[_p: P]: P[String] = P(Start ~ ident ~ End)
  def isLegalIdentifier(identifier: String): Boolean =
    parse(identifier, standaloneIdent(_)).isInstanceOf[Parsed.Success[_]]

  private def parseSelector(input: String): Parsed[(Option[Segments], Segments)] = {
    def ident2[_p: P] = P(CharsWhileIn("a-zA-Z0-9_\\-.")).!
    def segment[_p: P] = P(ident).map(Segment.Label)
    def crossSegment[_p: P] = P("[" ~ ident2.rep(1, sep = ",") ~ "]").map(Segment.Cross)
    def simpleQuery[_p: P] = P(segment ~ ("." ~ segment | crossSegment).rep).map {
      case (h, rest) => Segments(h, rest: _*)
    }
    def query[_p: P] = P(simpleQuery ~ ("/" ~/ simpleQuery).?).map {
      case (q, None) => (None, q)
      case (q, Some(q2)) => (Some(q), q2)
    }
    parse(input, query(_))
  }
}
