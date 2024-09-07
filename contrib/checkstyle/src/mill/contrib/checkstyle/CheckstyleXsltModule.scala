package mill
package contrib.checkstyle

import mill.api.PathRef

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Extends [[CheckstyleModule]] with the ability to apply transformations on the generated `checkstyle` XML report.
 */
trait CheckstyleXsltModule extends CheckstyleModule {

  /** A function that applies an XSLT */
  type Processor = XsltInput => Xslt => XsltOutput => Unit

  /** A path to an XSLT */
  type Xslt = os.Path

  /** A path to the input of an XSLT */
  type XsltInput = os.Path

  /** A path to the output of an XSLT */
  type XsltOutput = os.Path

  /** A tuple of a path to an XSLT and the relative path to its output */
  type XsltTarget = (PathRef, Seq[String])

  /**
   * Generates a `checkstyle` XML report and applies transformations on it.
   */
  override def checkstyle: T[PathRef] = T {

    val dst = T.dest
    val in = super.checkstyle()
    val targets = checkstyleXsltTargets()

    val f = checkstyleProcessor(in.path)
    targets.foreach {
      case (ref, rel) =>
        val xslt = ref.path
        val out = dst / rel
        T.log.info(s"transforming checkstyle report using $xslt")
        f(xslt)(out)
        T.log.info(s"transformed checkstyle report to $out")
    }

    in
  }

  override final def checkstyleFormat: T[String] = T {
    "xml"
  }

  def checkstyleProcessor: Processor = {

    def result(path: os.Path): StreamResult =
      new StreamResult(os.write.outputStream(path, createFolders = true))

    def source(path: os.Path): StreamSource =
      new StreamSource(path.getInputStream)

    input =>
      xslt =>
        output =>
          TransformerFactory.newInstance()
            .newTransformer(source(xslt))
            .transform(source(input), result(output))
  }

  /**
   * Directory containing XSLT files.
   * Defaults to `xslt` under [[checkstyleDir]].
   */
  def checkstyleXsltDir: T[PathRef] = T.source {
    checkstyleDir().path / "xslt"
  }

  /**
   * A set of [[XsltTarget target]]s to be applied on a `checkstyle` XML report.
   * The implementation scans for XSLT files under the [[checkstyleXsltDir]].
   *
   * == XSLT scan ==
   * The scan process is best illustrated with an example.
   * {{{
   * /*
   *
   * checkstyle
   *  ├─ xslt
   *  │  ├─ html
   *  │  │   ├─ README.txt
   *  │  │   ├─ xslt0.xml
   *  │  │   └─ xslt1.xml
   *  │  └─ pdf
   *  │      ├─ xslt1.xml
   *  │      └─ xslt2.xml
   *  └─ config.xml
   *
   * The directory structure above results in the following mapping:
   *  - checkstyle/xslt/html/xslt0.xml -> xslt0.html
   *  - checkstyle/xslt/html/xslt1.xml -> xslt1.html
   *  - checkstyle/xslt/pdf/xslt1.xml  -> xslt1.pdf
   *  - checkstyle/xslt/pdf/xslt2.xml  -> xslt2.pdf
   *
   * */
   * }}}
   */
  def checkstyleXsltTargets: T[Set[XsltTarget]] = T[Set[XsltTarget]] {
    val xslt = checkstyleXsltDir().path
    T.log.info(s"scanning (for XSLT files) $xslt")
    os.list(xslt)
      .iterator
      .filter(os.isDir)
      .flatMap { ext =>
        os.list(ext)
          .iterator
          .filter(_.ext == "xml")
          .map(xslt => PathRef(xslt) -> Seq(s"${xslt.baseName}.${ext.baseName}"))
      }
      .toSet
  }
}
