package mill.util

import fastparse._, NoWhitespace._
import mill.define.{Segment, Segments}

object ParseArgs {

  def apply(scriptArgs: Seq[String],
            multiSelect: Boolean): Either[String, (List[(Option[Segments], Segments)], Seq[String])] = {
    val (selectors, args) = extractSelsAndArgs(scriptArgs, multiSelect)
    for {
      _ <- validateSelectors(selectors)
      expandedSelectors <- EitherOps
        .sequence(selectors.map(expandBraces))
        .map(_.flatten)
      selectors <- EitherOps.sequence(expandedSelectors.map(extractSegments))
    } yield (selectors.toList, args)
  }

  def extractSelsAndArgs(scriptArgs: Seq[String],
                         multiSelect: Boolean): (Seq[String], Seq[String]) = {

    if (multiSelect) {
      val dd = scriptArgs.indexOf("--")
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
      case f: Parsed.Failure           => Left(s"Parsing exception ${f.msg}")
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
            case Keep(v)          => Seq(v)
            case Expand(Nil)      => Seq("{}")
            case Expand(List(vs)) => unfold(vs).map("{" + _ + "}")
            case Expand(vss)      => vss.flatMap(unfold)
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
    def plainChars[_: P] =
      P(CharsWhile(c => c != ',' && c != '{' && c != '}')).!.map(Fragment.Keep)

    def toExpand[_: P]: P[Fragment] =
      P("{" ~ braceParser.rep(1).rep(sep = ",") ~ "}").map(
        x => Fragment.Expand(x.toList.map(_.toList))
      )

    def braceParser[_: P] = P(toExpand | plainChars)

    def parser[_: P] = P(braceParser.rep(1).rep(sep = ",") ~ End).map { vss =>
      def unfold(vss: List[Seq[String]]): Seq[String] = {
        vss match {
          case Nil => Seq("")
          case head :: rest =>
            for {
              str <- head
              r <- unfold(rest)
            } yield
              r match {
                case "" => str
                case _  => str + "," + r
              }
        }
      }

      val stringss = vss.map(x => Fragment.unfold(x.toList)).toList
      unfold(stringss)
    }
  }

  private def parseBraceExpansion(input: String) = {


      parse(
        input,
        BraceExpansionParser.parser(_)
      )
  }

  def extractSegments(selectorString: String): Either[String, (Option[Segments], Segments)] =
    parseSelector(selectorString) match {
      case f: Parsed.Failure           => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }

  private def ident[_: P] = P( CharsWhileIn("a-zA-Z0-9_\\-") ).!

  def standaloneIdent[_: P] = P(Start ~ ident ~ End )
  def isLegalIdentifier(identifier: String): Boolean =
    parse(identifier, standaloneIdent(_)).isInstanceOf[Parsed.Success[_]]

  private def parseSelector(input: String) = {
    def ident2[_: P] = P( CharsWhileIn("a-zA-Z0-9_\\-.") ).!
    def segment[_: P] = P( ident ).map( Segment.Label)
    def crossSegment[_: P] = P("[" ~ ident2.rep(1, sep = ",") ~ "]").map(Segment.Cross)
    def simpleQuery[_: P] = P(segment ~ ("." ~ segment | crossSegment).rep).map {
      case (h, rest) => Segments(h :: rest.toList:_*)
    }
    def query[_: P] = P( simpleQuery ~ ("/" ~/ simpleQuery).?).map{
      case (q, None) => (None, q)
      case (q, Some(q2)) => (Some(q), q2)
    }
    parse(input, query(_))
  }
}
