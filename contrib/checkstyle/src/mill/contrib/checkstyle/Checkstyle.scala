package mill.contrib.checkstyle

import mill.api.PathRef

/**
 * [[CheckstyleModule]] output.
 *
 * @param errors number of errors found
 * @param report Checkstyle report
 * @param transformations transformations applied on the Checkstyle report
 */
case class Checkstyle(errors: Int, report: PathRef, transformations: Seq[PathRef])
object Checkstyle {

  import upickle.default._

  implicit val CheckstyleReportRW: ReadWriter[Checkstyle] = macroRW
}
