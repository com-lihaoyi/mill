package mill.main

import mill.util.EitherOps
import fastparse.all._
import mill.define.Segment

object ParseArgs {

  def apply(
    scriptArgs: Seq[String]
  ): Either[String, (List[List[Segment]], Seq[String])] = {
    val (selectors, args, isMultiSelectors) = extractSelsAndArgs(scriptArgs)
    for {
      _ <- validateSelectors(selectors)
      expandedSelectors <- EitherOps
        .sequence(selectors.map(expandBraces))
        .map(_.flatten)
      _ <- validateExpanded(expandedSelectors, isMultiSelectors)
      selectors <- EitherOps.sequence(expandedSelectors.map(extractSegments))
    } yield (selectors.toList, args)
  }

  def extractSelsAndArgs(
    scriptArgs: Seq[String]
  ): (Seq[String], Seq[String], Boolean) = {
    val multiFlags = Seq("--all", "--seq")
    val isMultiSelectors = scriptArgs.headOption.exists(multiFlags.contains)

    if (isMultiSelectors) {
      val dd = scriptArgs.indexOf("--")
      val selectors = (if (dd == -1) scriptArgs
                       else scriptArgs.take(dd)).filterNot(multiFlags.contains)
      val args = if (dd == -1) Seq.empty else scriptArgs.drop(dd + 1)

      (selectors, args, isMultiSelectors)
    } else {
      (scriptArgs.take(1), scriptArgs.drop(1), isMultiSelectors)
    }
  }

  private def validateSelectors(
    selectors: Seq[String]
  ): Either[String, Unit] = {
    if (selectors.isEmpty || selectors.exists(_.isEmpty))
      Left("Selector cannot be empty")
    else Right(())
  }

  private def validateExpanded(expanded: Seq[String],
                               isMulti: Boolean): Either[String, Unit] = {
    if (!isMulti && expanded.length > 1)
      Left("Please use --all flag to run multiple tasks")
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
    val plainChars =
      P(CharsWhile(c => c != ',' && c != '{' && c != '}')).!.map(Fragment.Keep)

    val toExpand: P[Fragment] =
      P("{" ~ braceParser.rep(1).rep(sep = ",") ~ "}")
        .map(x => Fragment.Expand(x.toList.map(_.toList)))

    val braceParser = P(toExpand | plainChars)

    val parser = P(braceParser.rep(1).rep(sep = ",") ~ End)
  }

  private def parseBraceExpansion(input: String) = {
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

    BraceExpansionParser.parser
      .map { vss =>
        val stringss = vss.map(x => Fragment.unfold(x.toList)).toList
        unfold(stringss)
      }
      .parse(input)
  }

  def extractSegments(selectorString: String): Either[String, List[Segment]] =
    parseSelector(selectorString) match {
      case f: Parsed.Failure           => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }

  private def parseSelector(input: String) = {
    val segment =
      P(CharsWhileIn(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).!)
        .map(Segment.Label)
    val crossSegment =
      P("[" ~ CharsWhile(c => c != ',' && c != ']').!.rep(1, sep = ",") ~ "]")
        .map(Segment.Cross)
    val query = P(segment ~ ("." ~ segment | crossSegment).rep ~ End).map {
      case (h, rest) => h :: rest.toList
    }
    query.parse(input)
  }

}
