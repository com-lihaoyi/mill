package mill
package contrib.checkstyle

import mill.api.PathRef

trait CheckstyleXsltModule extends CheckstyleModule {

  override def checkstyle: T[PathRef] = T {

    val dst = T.dest
    val out = super.checkstyle()
    val transforms = checkstyleXslt()

    val src = out.path
    transforms.foreach {
      case (use, out) =>
        val xslt = use.path
        val res = dst / out
        T.log.info(s"transforming checkstyle report to ${res.ext} ...")
        T.log.info(s"  $xslt")
        JavaXml.transform(xslt)(src, res)
        T.log.info("transformed checkstyle report")
        T.log.info(s"  $res")
    }

    out
  }

  override final def checkstyleFormat: T[String] = T {
    "xml"
  }

  def checkstyleXslt: T[Set[(PathRef, Seq[String])]] = T {
    val xslt = checkstyleXsltDir().path
    T.log.info("scanning for transforms ...")
    T.log.info(s"  $xslt")
    os.walk(xslt, maxDepth = 1)
      .iterator
      .filter(os.isDir)
      .flatMap { ext =>
        os.walk(ext, maxDepth = 1)
          .iterator
          .filter(_.ext == "xml")
          .map(path => PathRef(path) -> Seq(s"${path.baseName}.${ext.baseName}"))
      }
      .toSet
  }

  def checkstyleXsltDir: T[PathRef] = T.source {
    checkstyleDir().path / "xslt"
  }
}
