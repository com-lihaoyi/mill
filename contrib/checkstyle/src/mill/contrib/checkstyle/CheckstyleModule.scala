package mill
package contrib.checkstyle

import mill.api.{Loose, PathRef}
import mill.scalalib.{DepSyntax, JavaModule, ScalaModule}
import mill.util.Jvm

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}

trait CheckstyleModule extends JavaModule {

  import CheckstyleModule._

  def checkstyle: T[Seq[PathRef]] = T {

    val dst = T.dest

    val report = checkstyleReport().path
    val format = checkstyleFormat()
    val transforms = checkstyleTransforms()
    val transformer = checkstyleTransformer(format)

    val transformations = transformer.fold(Seq.empty[PathRef]) { transformer =>
      val f = transformer(report)
      transforms.map {
        case (ref, rel) =>
          val transform = ref.path
          val out = dst / rel
          T.log.info(s"transforming checkstyle report using $transform")
          f(transform, out)
          T.log.info(s"transformed checkstyle report to $out")
          PathRef(out)
      }.toSeq
    }

    transformations
  }

  def checkstyleClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}")
    )
  }

  def checkstyleConfig: T[PathRef] = T.source {
    checkstyleDir().path / "config.xml"
  }

  def checkstyleDir: T[PathRef] = T {
    PathRef(millSourcePath / "checkstyle")
  }

  def checkstyleFormat: T[String] = T {
    "xml"
  }

  def checkstyleOptions: T[Seq[String]] = T {
    if (isScala) Seq("-x", ".*[\\.]scala") else Seq.empty
  }

  def checkstyleOutput: T[String] = T {
    val fmt = checkstyleFormat()
    val ext = if (fmt == "plain") "txt" else fmt
    s"report.$ext"
  }

  def checkstyleReport: T[PathRef] = T {
    val classpath = checkstyleClasspath().map(_.path)
    val options = checkstyleOptions()
    val config = checkstyleConfig()
    val format = checkstyleFormat()
    val inputs = sources().map(_.path.toString())
    val _throw = checkstyleThrow()
    val report = T.dest / checkstyleOutput()

    val args = options ++
      Seq("-c", config.path.toString(), "-f", format, "-o", report.toString()) ++
      inputs

    T.log.info(s"generating checkstyle $format report ...")
    T.log.debug(s"running checkstyle with $args")

    val errs = Jvm.callSubprocessUnchecked(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = classpath,
      mainArgs = args,
      workingDir = T.dest
    ).exitCode

    if (errs == 0) {
      T.log.info("checkstyle passed")
    } else if (errs > 0 && os.exists(report)) {
      T.log.error(s"checkstyle found $errs error(s), details in $report")
      if (_throw) {
        throw new RuntimeException(s"checkstyle found $errs error(s)")
      }
    } else {
      T.log.error(
        s"checkstyle aborted, please check plugin settings or try a different Checkstyle version"
      )
      throw new UnsupportedOperationException(s"checkstyle exit($errs)")
    }

    PathRef(report)
  }

  /**
   * A set of [[Transform transformation]]s to be applied on a `checkstyle` report.
   *
   * The implementation scans for files under the [[checkstyleDir]] `/` [[checkstyleFormat]].
   * The selection process is best illustrated with an example.
   * {{{
   * /*
   *
   * checkstyle
   *  ├─ xml
   *  │  ├─ html
   *  │  │   ├─ xslt0.xml
   *  │  │   └─ xslt1.xml
   *  │  └─ pdf
   *  │      ├─ xslt1.xml
   *  │      └─ xslt2.xml
   *  └─ config.xml
   *
   * The directory structure above results in the following mapping:
   *  - checkstyle/xml/html/xslt0.xml -> xslt0.html
   *  - checkstyle/xml/html/xslt1.xml -> xslt1.html
   *  - checkstyle/xml/pdf/xslt1.xml  -> xslt1.pdf
   *  - checkstyle/xml/pdf/xslt2.xml  -> xslt2.pdf
   *
   * */
   * }}}
   */
  def checkstyleTransforms: T[Set[Transform]] = T[Set[Transform]] {
    val dir = checkstyleDir().path / checkstyleFormat()

    if (os.exists(dir)) {
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
    } else Set.empty[Transform]
  }

  def checkstyleTransformer(format: String): Option[Transformer] =
    Option.when(format == "xml")(Transformer.Xml)

  def checkstyleThrow: T[Boolean] = T {
    true
  }

  def checkstyleVersion: T[String] = T {
    "10.18.1"
  }

  private def isScala = this.isInstanceOf[ScalaModule]
}

object CheckstyleModule {

  /** A path to a transformation and a relative path for it's output */
  type Transform = (PathRef, Seq[String])

  /** A function that applies a transformation */
  type Transformer = os.Path => (os.Path, os.Path) => Unit

  object Transformer {

    val Xml: Transformer = {
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
}
