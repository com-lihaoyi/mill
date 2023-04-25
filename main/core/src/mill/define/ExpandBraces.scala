package mill.define
import fastparse._
import fastparse.NoWhitespace.noWhitespaceImplicit

object ExpandBraces{
  private sealed trait Fragment
  private object Fragment {
    case class Keep(value: String) extends Fragment
    case class Expand(values: List[List[Fragment]]) extends Fragment
  }

  def expandBraces(selectorString: String): Either[String, Seq[String]] = {
    parse(selectorString, parser(_)) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(fragmentLists, _) =>
        def processFragmentSequence(remaining: List[Fragment]): List[List[String]] =
          remaining match {
            case Nil => List(List())
            case head :: tail =>
              val tailStrings = processFragmentSequence(tail)
              head match {
                case Fragment.Keep(s) => tailStrings.map(s :: _)
                case Fragment.Expand(fragmentLists) =>
                  if (fragmentLists == Nil) tailStrings.map("{}" :: _)
                  else for {
                    lhs <- fragmentLists.flatMap(processFragmentSequence)
                    rhs <- tailStrings
                  } yield lhs ::: rhs
              }
          }

        val res = processFragmentSequence(fragmentLists.toList).map(_.mkString)

        Right(res)
    }
  }

  private def plainChars[_p: P]: P[Fragment.Keep] =
    P(CharsWhile(c => c != ',' && c != '{' && c != '}')).!.map(Fragment.Keep)

  private def toExpand[_p: P]: P[Fragment] =
    P("{" ~ braceParser.rep(1).rep(sep = ",") ~ "}").map(x =>
      Fragment.Expand(x.toList.map(_.toList))
    )

  private def braceParser[_p: P]: P[Fragment] = P(toExpand | plainChars)

  private def topLevelComma[_p: P] = P(",".!).map(Fragment.Keep(_))

  private def parser[_p: P]: P[Seq[Fragment]] = P((braceParser | topLevelComma).rep(1) ~ End)
}
