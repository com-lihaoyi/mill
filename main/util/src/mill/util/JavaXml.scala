package mill.util

import javax.xml.transform.stream.StreamSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.TransformerFactory

object JavaXml {

  def result(path: os.Path, createFolders: Boolean = true): StreamResult =
    new StreamResult(os.write.outputStream(path, createFolders = createFolders))

  def source(path: os.Path): StreamSource =
    new StreamSource(path.getInputStream)

  def transform(xslt: os.Path)(src: os.Path, res: os.Path, createFolders: Boolean = true): Unit =
    TransformerFactory.newInstance()
      .newTransformer(source(xslt))
      .transform(source(src), result(res, createFolders))
}
