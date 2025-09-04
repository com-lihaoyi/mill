package mill.javalib.checkstyle

import mill.api.PathRef

/**
 * A report obtained by transforming a Checkstyle output report.
 *
 * @param xslt path to an [[https://www.w3.org/TR/xslt/ XSLT]] file
 * @param output path to the transformed output report
 */
case class CheckstyleXsltReport(xslt: PathRef, output: PathRef)
object CheckstyleXsltReport {

  import upickle._

  implicit val RW: ReadWriter[CheckstyleXsltReport] = macroRW[CheckstyleXsltReport]
}
