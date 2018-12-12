package mill.scalalib.dependency.versions

import fastparse._, NoWhitespace._

private[dependency] object VersionParser {

  private def numberParser[_: P] =
    P(CharIn("0-9").rep(1).!.map(_.toLong))
  private def numericPartParser[_: P] =
    P(numberParser ~ &(CharIn(".\\-+") | End)).rep(min = 1, sep = ".")

  private def tokenParser[_: P] =
    CharPred(c => c != '.' && c != '-' && c != '+').rep(1).!
  private def tokenPartParser[_: P] =
    tokenParser.rep(sep = CharIn(".\\-"))

  private def firstPartParser[_: P] =
    P(CharIn(".\\-") ~ tokenPartParser).?

  private def secondPartParser[_: P] =
    P("+" ~ tokenPartParser).?

  private def versionParser[_: P] =
    P(numericPartParser ~ firstPartParser ~ secondPartParser).map {
      case (a, b, c) => (a, b.getOrElse(Seq.empty), c.getOrElse(Seq.empty))
    }

  def parse(text: String): Parsed[(Seq[Long], Seq[String], Seq[String])] =
    fastparse.parse(text, versionParser(_))
}
