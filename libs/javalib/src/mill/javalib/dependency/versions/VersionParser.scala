package mill.javalib.dependency.versions

import fastparse._
import fastparse.NoWhitespace._

private[dependency] object VersionParser {

  private def numberParser[_p: P] =
    P(CharIn("0-9").rep(1).!.map(_.toLong))
  private def numericPartParser[_p: P] =
    P(numberParser ~ &(CharIn(".\\-+") | End)).rep(min = 1, sep = ".")

  private def tokenParser[_p: P] =
    CharPred(c => c != '.' && c != '-' && c != '+').rep(1).!
  private def tokenPartParser[_p: P] =
    tokenParser.rep(sep = CharIn(".\\-"))

  private def firstPartParser[_p: P] =
    P(CharIn(".\\-") ~ tokenPartParser).?

  private def secondPartParser[_p: P] =
    P("+" ~ tokenPartParser).?

  private def versionParser[_p: P] =
    P(numericPartParser ~ firstPartParser ~ secondPartParser).map {
      case (a, b, c) => (a, b.getOrElse(Seq.empty), c.getOrElse(Seq.empty))
    }

  def parse(text: String): Parsed[(Seq[Long], Seq[String], Seq[String])] =
    fastparse.parse(text, versionParser(using _))
}
