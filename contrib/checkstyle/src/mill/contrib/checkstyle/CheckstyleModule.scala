package mill
package contrib.checkstyle

import mill.api.{Loose, PathRef}
import mill.scalalib.{DepSyntax, JavaModule}
import mill.util.Jvm

/**
 * Performs quality checks on Java source files using [[https://checkstyle.org/ Checkstyle]] and generates reports from these checks.
 */
trait CheckstyleModule extends JavaModule {

  /**
   * Generates a [[CheckstyleOutput]].
   */
  def checkstyle: T[CheckstyleOutput] = T {

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
      // errors is always 255 ?
      // is the byte value of -1 being converted to an unsigned int somewhere in the call stack ???
      // have to throw here, might as well use the byte value
      val exit = errors.toByte
      T.log.error(
        s"checkstyle exit($exit), please check plugin settings or try with another version"
      )
      throw new UnsupportedOperationException(s"checkstyle exit($exit)")
    }
    val transformations =
      getCheckstyleTransformer(report).fold(Set.empty[CheckstyleTransformation]) { processor =>
        checkstyleTransformations().map { transformation =>
          T.log.info(s"transforming checkstyle report with ${transformation.definition}")
          processor(transformation)
          T.log.info(s"transformed checkstyle report to ${transformation.output}")
          transformation
        }
      }

    CheckstyleOutput(errors, PathRef(report), transformations)
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
   * Defines a set of [[CheckstyleTransformation]]s.
   *
   * The implementation identifies transformations from the contents of [[checkstyleDir]].
   * The selection process is best illustrated with an example.
   * {{{
   * /*
   * Directory structure:
   *
   *    checkstyle
   *        ├─ html
   *        │   ├─ xslt0.xml
   *        │   └─ xslt1.xml
   *        ├─ pdf
   *        │   ├─ xslt1.xml
   *        │   └─ xslt2.xml
   *        └─ config.xml
   *
   * Transformations:
   *
   *  - checkstyle/html/xslt0.xml -> xslt0.html
   *  - checkstyle/html/xslt1.xml -> xslt1.html
   *  - checkstyle/pdf/xslt1.xml  -> xslt1.pdf
   *  - checkstyle/pdf/xslt2.xml  -> xslt2.pdf
   *
   * */
   * }}}
   */
  def checkstyleTransformations: T[Set[CheckstyleTransformation]] = T {
    val definitionDir = checkstyleDir().path
    val outputDir = T.dest

    val transformations = if (os.exists(definitionDir)) {
      T.log.info(s"scanning $definitionDir for transformations ...")
      os.list(definitionDir)
        .iterator
        .filter(os.isDir)
        .flatMap { ext =>
          os.list(ext)
            .iterator
            .filter(os.isFile)
            .map(definition =>
              CheckstyleTransformation(
                PathRef(definition),
                PathRef(outputDir / s"${definition.baseName}.${ext.baseName}")
              )
            )
        }
        .toSet
    } else {
      Set.empty[CheckstyleTransformation]
    }

    T.log.info(s"found ${transformations.size} transformation(s)")

    transformations
  }

  /**
   * Checkstyle version. Defaults to `10.18.1`.
   */
  def checkstyleVersion: T[String] = T {
    "10.18.1"
  }

  /**
   * A [[CheckstyleTransformer]] for the Checkstyle `report`, if transformations are supported.
   */
  def getCheckstyleTransformer(report: os.Path): Option[CheckstyleTransformer] =
    Option.when(report.ext == "xml")(CheckstyleTransformer.xml(report))
}
