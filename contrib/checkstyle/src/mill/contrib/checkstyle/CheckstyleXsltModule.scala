package mill
package contrib.checkstyle

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Extends [[CheckstyleModule]] with the ability to generate [[CheckstyleXsltReport]]s.
 */
trait CheckstyleXsltModule extends CheckstyleModule {

  /**
   * Runs [[CheckstyleModule.checkstyle]] and uses [[CheckstyleModule.checkstyleOutput]] to generate [[checkstyleXsltReports]].
   */
  override def checkstyle(@mainargs.arg checkstyleArgs: CheckstyleArgs): Command[Int] = T.command {
    val numViolations = super.checkstyle(checkstyleArgs.copy(check = false, stdout = false))()
    val checkOutput = checkstyleOutput().path

    if (os.exists(checkOutput)) {
      checkstyleXsltReports().foreach {
        case CheckstyleXsltReport(xslt, output) =>
          val xsltSource = new StreamSource(xslt.path.getInputStream)
          xsltSource.setSystemId(xslt.path.toIO) // so that relative URI references can be resolved

          val checkSource =
            new StreamSource(checkOutput.getInputStream)

          val outputResult =
            new StreamResult(os.write.outputStream(output.path, createFolders = true))

          T.log.info(s"transforming checkstyle output report with $xslt")

          TransformerFactory.newInstance()
            .newTransformer(xsltSource)
            .transform(checkSource, outputResult)

          T.log.info(s"transformed output report at $output")
      }
    }

    numViolations
  }

  /**
   * `xml`
   */
  final override def checkstyleFormat: T[String] = T {
    "xml"
  }

  /**
   * Set of [[CheckstyleXsltReport]]s.
   *
   * The default implementation maps XSLT files, under `checkstyle-xslt`, as depicted below:
   * {{{
   *
   * checkstyle-xslt
   *  ├─ html
   *  │   ├─ xslt0.xml
   *  │   └─ xslt1.xml
   *  └─ pdf
   *      ├─ xslt1.xml
   *      └─ xslt2.xml
   *
   * html/xslt0.xml -> xslt0.html
   * html/xslt1.xml -> xslt1.html
   * pdf/xslt1.xml  -> xslt1.pdf
   * pdf/xslt2.xml  -> xslt2.pdf
   *
   * }}}
   */
  def checkstyleXsltReports: T[Set[CheckstyleXsltReport]] = T {
    val dir = millSourcePath / "checkstyle-xslt"

    if (os.exists(dir)) {
      val dest = T.dest
      os.list(dir)
        .iterator
        .filter(os.isDir)
        .flatMap(childDir =>
          os.list(childDir)
            .iterator
            .filter(os.isFile)
            .filter(_.ext == "xml")
            .map(xslt =>
              CheckstyleXsltReport(
                PathRef(xslt),
                PathRef(dest / s"${xslt.baseName}.${childDir.last}")
              )
            )
        )
        .toSet
    } else {
      T.log.info(s"expected XSLT files under $dir")
      Set.empty[CheckstyleXsltReport]
    }
  }
}
