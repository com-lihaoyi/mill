package mill.contrib.checkstyle

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Applies a [[CheckstyleTransformation]].
 */
trait CheckstyleTransformer extends (CheckstyleTransformation => Unit)
object CheckstyleTransformer {

  /**
   * Returns a [[CheckstyleTransformer]] that applies a [[CheckstyleTransformation]] on the Checkstyle XML `report`.
   */
  def xml(report: os.Path): CheckstyleTransformer = (transformation: CheckstyleTransformation) => {

    def result(path: os.Path): StreamResult =
      new StreamResult(os.write.outputStream(path, createFolders = true))

    def source(path: os.Path): StreamSource =
      new StreamSource(path.getInputStream)

    TransformerFactory.newInstance()
      .newTransformer(source(transformation.definition.path))
      .transform(source(report), result(transformation.output.path))
  }
}
