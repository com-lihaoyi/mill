package mill.contrib.checkstyle

import mill.api.PathRef

/**
 * [[CheckstyleModule]] output.
 *
 * @param errors number of errors found
 * @param report Checkstyle report
 * @param outputs [[CheckstyleTransformation]]s applied on `report`
 */
case class CheckstyleOutput(errors: Int, report: PathRef, outputs: Set[CheckstyleTransformation])
object CheckstyleOutput {

  import upickle.default._

  implicit val RW: ReadWriter[CheckstyleOutput] = macroRW
}
