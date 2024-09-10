package mill.contrib.checkstyle

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Generates a [[TransformedReport]].
 */
trait ReportGenerator extends (TransformedReport => Unit)
object ReportGenerator {

  /**
   * Returns a [[ReportGenerator]] that transforms the `xmlReport`.
   */
  def xml(xmlReport: os.Path): ReportGenerator = {
    case TransformedReport(transformation, report) =>
      def result(path: os.Path): StreamResult =
        new StreamResult(os.write.outputStream(path, createFolders = true))

      def source(path: os.Path): StreamSource =
        new StreamSource(path.getInputStream)

      TransformerFactory.newInstance()
        .newTransformer(source(transformation.path))
        .transform(source(xmlReport), result(report.path))
  }
}
