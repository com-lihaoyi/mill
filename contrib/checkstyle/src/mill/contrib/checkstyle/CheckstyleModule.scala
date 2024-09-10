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
   * Command that returns a value obtained by running [[https://checkstyle.org/ Checkstyle]].
   *
   * Checkstyle writes the output report at [[checkstyleReport]]. This is used to generate [[checkstyleTransformedReports]].
   *
   * @note [[sources]] are processed when no [[CheckstyleArgs.files]] are specified.
   *
   * @return number of violations, or, Checkstyle exit code
   */
  def checkstyle(@mainargs.arg checkstyleArgs: CheckstyleArgs): Command[Int] = T.command {

    val CheckstyleArgs(check, stdout, files) = checkstyleArgs

    val report = checkstyleReport().path
    val args = checkstyleOptions() ++
      Seq("-c", checkstyleConfig().path.toString()) ++
      Seq("-f", checkstyleFormat()) ++
      (if (stdout) Seq.empty else Seq("-o", report.toString())) ++
      (if (files.value.isEmpty) sources().map(_.path.toString()) else files.value)

    T.log.info("running checkstyle ...")
    T.log.debug(s"with $args")

    val exitCode = Jvm.callSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = checkstyleClasspath().map(_.path),
      mainArgs = args,
      workingDir =
        millSourcePath, // allows for passing relative paths for `files` like src/a/b/c
      check = false
    ).exitCode

    val reported = os.exists(report)
    if (reported) {
      T.log.info(s"generated checkstyle report at $report")
    }

    if (exitCode == 0) {
      T.log.info("checkstyle found no violation")
    } else if (exitCode < 0 || !(reported || stdout)) {
      T.log.error(
        s"checkstyle exit($exitCode), please check command arguments, plugin settings or try with another version"
      )
      throw new UnsupportedOperationException(s"checkstyle exit($exitCode)")
    } else if (check) {
      throw new RuntimeException(s"checkstyle found $exitCode violation(s)")
    } else {
      T.log.error(s"checkstyle found $exitCode violation(s)")
    }

    if (reported) {
      getCheckstyleReportGenerator(report).foreach { generator =>
        checkstyleTransformedReports().foreach { t =>
          T.log.info(s"transforming checkstyle report with ${t.transformation}")
          generator(t)
          T.log.info(s"generated transformed report at ${t.report}")
        }
      }
    }

    exitCode
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
   * Checkstyle report format (` plain | sarif | xml `). Defaults to `xml`.
   */
  def checkstyleFormat: T[String] = T {
    "xml"
  }

  /**
   * Additional arguments for Checkstyle.
   *
   * @see [[https://checkstyle.org/cmdline.html#Command_line_usage Checkstyle CLI options]]
   */
  def checkstyleOptions: T[Seq[String]] = T {
    Seq.empty[String]
  }

  /**
   * Checkstyle output report.
   */
  def checkstyleReport: T[PathRef] = T {
    PathRef(T.dest / s"report.${checkstyleFormat()}")
  }

  /**
   * [[TransformedReport]]s generated from [[checkstyleReport]].
   *
   * The implementation loads transformations from [[checkstyleDir]].
   * The identification process is best illustrated with an example.
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
  def checkstyleTransformedReports: T[Set[TransformedReport]] = T {
    val transformationDir = checkstyleDir().path
    val reportDir = T.dest

    if (os.exists(transformationDir)) {
      T.log.info(s"scanning for transformations under $transformationDir")
      os.list(transformationDir)
        .iterator
        .filter(os.isDir)
        .flatMap { ext =>
          os.list(ext)
            .iterator
            .filter(os.isFile)
            .map(transformation =>
              TransformedReport(
                PathRef(transformation),
                PathRef(reportDir / s"${transformation.baseName}.${ext.baseName}")
              )
            )
        }
        .toSet
    } else {
      Set.empty[TransformedReport]
    }
  }

  /**
   * Checkstyle version. Defaults to `10.18.1`.
   */
  def checkstyleVersion: T[String] = T {
    "10.18.1"
  }

  /**
   * Returns a [[ReportGenerator]] for the Checkstyle `report`, if transformations are supported.
   */
  def getCheckstyleReportGenerator(report: os.Path): Option[ReportGenerator] =
    Option.when(report.ext == "xml")(ReportGenerator.xml(report))
}
