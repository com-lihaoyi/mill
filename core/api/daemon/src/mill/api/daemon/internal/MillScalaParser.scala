package mill.api.daemon.internal

import scala.util.DynamicVariable

trait MillScalaParser {
  def splitScript(
      rawCode: String,
      fileName: String,
      colored: Boolean
  ): Either[String, (String, Seq[String], Seq[String])]

  /* not sure if this is the right way, in case needs change, or if we should accept some
   * "generic" visitor over some "generic" trees?
   */
  def parseObjectData(rawCode: String): Seq[MillScalaParser.ObjectData]
}

object MillScalaParser {
  trait ObjectData {
    def obj: Snip

    def name: Snip

    def parent: Snip

    def endMarker: Option[Snip]

    def finalStat: Option[(String, Snip)]
  }

  trait Snip {
    def text: String | Null

    def start: Int

    def end: Int

    final def applyTo(s: String, replacement: String): String =
      s.patch(start, replacement.padTo(end - start, ' '), end - start)
  }

  val current = new DynamicVariable[MillScalaParser](null)
}
