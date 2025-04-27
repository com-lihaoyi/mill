package mill.javalib.checkstyle

import mill._
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Extends [[CheckstyleModule]] with the ability to generate [[CheckstyleXsltReport]]s.
 */
trait CheckstyleXsltModule extends CheckstyleModule {

  /**
   * Runs [[CheckstyleModule.checkstyle]] and uses [[CheckstyleModule.checkstyleOutput]] to generate [[checkstyleXsltReports]].
   */
  override def checkstyle(@mainargs.arg checkstyleArgs: CheckstyleArgs): Command[Int] =
    Task.Command {
      val (output, exitCode) = checkstyle0(false, checkstyleArgs.sources)()

      val checkOutput = checkstyleOutput().path

      if (os.exists(checkOutput)) {
        checkstyleXsltReports().foreach {
          case CheckstyleXsltReport(xslt, output) =>
            val xsltSource = new StreamSource(xslt.path.getInputStream)
            xsltSource.setSystemId(
              xslt.path.toIO
            ) // so that relative URI references can be resolved

            val checkSource =
              new StreamSource(checkOutput.getInputStream)

            val outputResult =
              new StreamResult(os.write.outputStream(output.path, createFolders = true))

            Task.log.info(s"transforming checkstyle output report with $xslt")

            TransformerFactory.newInstance()
              .newTransformer(xsltSource)
              .transform(checkSource, outputResult)

            Task.log.info(s"transformed output report at $output")
        }
      }

      checkstyleHandleErrors(checkstyleArgs.stdout, checkstyleArgs.check, exitCode, output)
    }

  /**
   * Necessary in order to allow XSLT transformations on the results
   */
  final override def checkstyleFormat: T[String] = "xml"

  /**
   * Folder containing the XSLT transformations. Defaults to `checkstyle-xslt`
   * in  the workspace root, but can be overridden on a per-module basis
   */
  def checkstyleXsltfFolder = Task.Source("checkstyle-xslt")

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
  def checkstyleXsltReports: T[Set[CheckstyleXsltReport]] = Task {
    val dir = checkstyleXsltfFolder().path

    if (os.exists(dir)) {
      val dest = Task.dest
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
      Task.log.info(s"expected XSLT files under $dir")
      Set.empty[CheckstyleXsltReport]
    }
  }
}
