package mill.scalalib.dependency.versions

import fastparse.all._

private[dependency] object VersionParser {

  private val numberParser =
    P(CharIn('0' to '9').rep(1).!.map(_.toLong))
  private val numericPartParser =
    P(numberParser ~ &(CharIn(".", "-", "+") | End)).rep(min = 1, sep = ".")

  private val tokenParser =
    CharPred(c => c != '.' && c != '-' && c != '+').rep(1).!
  private val tokenPartParser =
    tokenParser.rep(sep = CharIn(".", "-"))

  private val firstPartParser =
    P(CharIn(".", "-") ~ tokenPartParser).?

  private val secondPartParser =
    P("+" ~ tokenPartParser).?

  private val versionParser =
    P(numericPartParser ~ firstPartParser ~ secondPartParser).map {
      case (a, b, c) => (a, b.getOrElse(Seq.empty), c.getOrElse(Seq.empty))
    }

  def parse(text: String): Parsed[(Seq[Long], Seq[String], Seq[String])] =
    versionParser.parse(text)
}
