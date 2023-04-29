package mill.main

import fastparse.NoWhitespace.noWhitespaceImplicit
import fastparse._

object ExpandBraces {
  private sealed trait Fragment
  private object Fragment {
    case class Keep(value: String) extends Fragment
    case class Expand(values: List[List[Fragment]]) extends Fragment
  }

  def expandRec(frags: List[Fragment]): List[List[String]] = frags match {
    case Nil => List(List())
    case head :: tail =>
      val tailStrings = expandRec(tail)
      head match {
        case Fragment.Keep(s) => tailStrings.map(s :: _)
        case Fragment.Expand(fragmentLists) =>
          if (fragmentLists.length == 1) {
            for {
              lhs <- fragmentLists.flatMap(expandRec)
              rhs <- tailStrings
            } yield List("{") ::: lhs ::: List("}") ::: rhs
          } else for {
            lhs <- fragmentLists.flatMap(expandRec)
            rhs <- tailStrings
          } yield lhs ::: rhs
      }
  }

  def expandBraces(selectorString: String): Either[String, Seq[String]] = {
    parse(selectorString, parser(_)) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(fragmentLists, _) =>
        Right(expandRec(fragmentLists.toList).map(_.mkString))
    }
  }

  private def plainChars[_p: P]: P[Fragment.Keep] =
    P(CharsWhile(c => c != ',' && c != '{' && c != '}')).!.map(Fragment.Keep)

  private def emptyExpansionBranch[_p: P] = P("").map(_ => List(Fragment.Keep("")))

  private def toExpand[_p: P]: P[Fragment] =
    P("{" ~ (braceParser.rep(1) | emptyExpansionBranch).rep(sep = ",") ~ "}")
      .map(x => Fragment.Expand(x.toList.map(_.toList)))

  private def braceParser[_p: P]: P[Fragment] = P(toExpand | plainChars)

  private def topLevelComma[_p: P] = P(",".!).map(Fragment.Keep(_))

  private def parser[_p: P]: P[Seq[Fragment]] = P((braceParser | topLevelComma).rep(1) ~ End)
}
