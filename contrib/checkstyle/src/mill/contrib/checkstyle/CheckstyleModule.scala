package mill
package contrib.checkstyle

import mill.api.{Loose, PathRef}
import mill.scalalib.{DepSyntax, JavaModule}
import mill.util.Jvm

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

/**
 * Performs quality checks on Java source files using [[https://checkstyle.org/ Checkstyle]] and generates reports from these checks.
 */
trait CheckstyleModule extends JavaModule {

  import CheckstyleModule._

  /**
   * Generates a [[Checkstyle]].
   */
  def checkstyle: T[Checkstyle] = T {

    val dest = T.dest

    val format = checkstyleFormat()
    val report = dest / s"checkstyle-report.$format"

    val args = checkstyleOptions() ++
      Seq(
        "-c",
        checkstyleConfig().path.toString(),
        "-f",
        format,
        "-o",
        report.toString()
      ) ++
      sources().map(_.path.toString())

    T.log.info("generating checkstyle report ...")
    T.log.debug(s"running checkstyle with $args")

    val errors = Jvm.callSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = checkstyleClasspath().map(_.path),
      mainArgs = args,
      workingDir = dest,
      check = false
    ).exitCode

    if (errors == 0) {
      T.log.info(s"checkstyle found no errors, details in $report")
    } else if (errors > 0 && os.exists(report)) {
      T.log.error(s"checkstyle found $errors error(s), details in $report")
    } else {
      // errors is always 255, assuming byte value is the exit code
      val exit = errors.toByte
      T.log.error(
        s"checkstyle exit($exit), please check plugin settings or try with another version"
      )
      throw new UnsupportedOperationException(s"checkstyle exit($exit)")
    }
    val transformations =
      checkstyleTransformer(format).fold(Seq.empty[PathRef]) { transformer =>
        val f = transformer(report)
        checkstyleTransforms().map {
          case (ref, rel) =>
            val transform = ref.path
            val out = dest / rel
            T.log.info(s"transforming checkstyle report using $transform")
            f(transform, out)
            T.log.info(s"transformed checkstyle report to $out")
            PathRef(out)
        }.toSeq
      }

    Checkstyle(errors, PathRef(report), transformations)
  }

  /**
   * Classpath for running Checkstyle.
   */
  def checkstyleClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}")
    )
  }

  /**
   * Checkstyle configuration file. Defaults to [[checkstyleDir]] `/` `config.xml`.
   */
  def checkstyleConfig: T[PathRef] = T.source {
    checkstyleDir().path / "config.xml"
  }

  /**
   * Directory containing Checkstyle configuration and transformations. Defaults to `checkstyle`.
   */
  def checkstyleDir: T[PathRef] = T {
    PathRef(millSourcePath / "checkstyle")
  }

  /**
   * Checkstyle report format. Defaults to `xml`.
   */
  def checkstyleFormat: T[String] = T {
    "xml"
  }

  /**
   * Additional arguments for Checkstyle.
   *
   * @see [[https://checkstyle.org/cmdline.html#Command_line_usage CLI options]]
   */
  def checkstyleOptions: T[Seq[String]] = T {
    Seq.empty[String]
  }

  /**
   * Returns a [[Transformer transformer]] for `format`, if supported.
   */
  def checkstyleTransformer(format: String): Option[Transformer] =
    Option.when(format == "xml")(XmlTransformer)

  /**
   * A set of [[Transform transformation]]s to be applied on a `checkstyle` report.
   *
   * The implementation scans for files under the [[checkstyleDir]].
   * The selection process is best illustrated with an example.
   * {{{
   * /*
   *
   * checkstyle
   *    ├─ html
   *    │   ├─ xslt0.xml
   *    │   └─ xslt1.xml
   *    ├─ pdf
   *    │   ├─ xslt1.xml
   *    │   └─ xslt2.xml
   *    └─ config.xml
   *
   * The directory structure above results in the following mapping:
   *  - checkstyle/html/xslt0.xml -> xslt0.html
   *  - checkstyle/html/xslt1.xml -> xslt1.html
   *  - checkstyle/pdf/xslt1.xml  -> xslt1.pdf
   *  - checkstyle/pdf/xslt2.xml  -> xslt2.pdf
   *
   * */
   * }}}
   */
  def checkstyleTransforms: T[Set[Transform]] = T[Set[Transform]] {
    val dir = checkstyleDir().path

    T.log.info(s"scanning for transformation files in $dir")
    os.list(dir)
      .iterator
      .filter(os.isDir)
      .flatMap { ext =>
        os.list(ext)
          .iterator
          .filter(os.isFile)
          .map(transform => PathRef(transform) -> Seq(s"${transform.baseName}.${ext.baseName}"))
      }
      .toSet
  }

  /**
   * Checkstyle version. Defaults to `10.18.1`.
   */
  def checkstyleVersion: T[String] = T {
    "10.18.1"
  }
}

object CheckstyleModule {

  /** A path to a transformation and a relative path for it's output. */
  type Transform = (PathRef, Seq[String])

  /** A function that performs a transformation. */
  type Transformer = os.Path => (os.Path, os.Path) => Unit

  val XmlTransformer: Transformer = {
    def result(path: os.Path): StreamResult =
      new StreamResult(os.write.outputStream(path, createFolders = true))

    def source(path: os.Path): StreamSource =
      new StreamSource(path.getInputStream)

    input =>
      (transform, output) =>
        TransformerFactory.newInstance()
          .newTransformer(source(transform))
          .transform(source(input), result(output))
  }
}
