package mill.contrib.checkstyle

import mill.api.PathRef

/**
 * A transformation that can be applied on a Checkstyle report.
 *
 * @param definition path to the transformation definition
 * @param output path to the transformation output
 */
case class CheckstyleTransformation(definition: PathRef, output: PathRef)
object CheckstyleTransformation {

  import upickle.default._

  implicit val RW: ReadWriter[CheckstyleTransformation] = macroRW
}
