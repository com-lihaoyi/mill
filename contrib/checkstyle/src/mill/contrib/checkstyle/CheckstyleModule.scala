package mill.contrib.checkstyle

import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel.CheckstyleSeverityLevel
import com.etsy.sbt.checkstyle.{
  CheckstyleConfigLocation,
  CheckstyleSeverityLevel,
  CheckstyleXSLTSettings
}
import mill.api.{Logger, PathRef}
import mill.{Agg, T}
import mill.scalalib.{Dep, DepSyntax, JavaModule}
import mill.util.Jvm
import net.sf.saxon.s9api.Processor
import os.Path

import java.io.File
import javax.xml.transform.stream.StreamSource

/**
 * Integrate Checkstyle into a [[JavaModule]].
 *
 * See https://checkstyle.sourceforge.io/
 */
trait CheckstyleModule extends JavaModule {

  /** The `checkstyle` version to use. Defaults to [[BuildInfo.checkstyleVersion]]. */
  def checkstyleVersion: T[String] = T.input {
    BuildInfo.checkstyleVersion
  }

  /**
   * The dependencies of the `checkstyle` compiler plugin.
   */
  def checkstyleDeps: T[Agg[Dep]] = T {
    Agg(
      ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}"
    )
  }

  /**
   * Classpath to use to run `checkstyle`
   */
  def checkstyleClasspath = T {
    defaultResolver().resolveDeps(checkstyleDeps())
  }

  /**
   * Runs checkstyle
   *
   * @param javaSource The Java source path.
   * @param outputFile The Checkstyle report output path.
   * @param configLocation The Checkstyle config location.
   * @param xsltTransformations XSLT transformations to apply.
   * @param severityLevel The severity level used to fail the build.
   */
  def checkstyle(
      javaSource: PathRef,
      resources: Seq[Path],
      outputFile: PathRef,
      configLocation: CheckstyleConfigLocation,
      xsltTransformations: Option[Set[CheckstyleXSLTSettings]],
      severityLevel: Option[CheckstyleSeverityLevel]
  ) = T {
    val outputLocation = outputFile.path
    val targetFolder = outputFile.path.toNIO.getParent.toFile
    val configFile = targetFolder + "/checkstyle-config.xml"

    targetFolder.mkdirs()

    val config = scala.xml.XML.loadString(configLocation.read(resources))
    scala.xml.XML.save(
      configFile,
      config,
      "UTF-8",
      xmlDecl = true,
      scala.xml.dtd.DocType(
        "module",
        scala.xml.dtd.PublicID(
          "-//Puppy Crawl//DTD Check Configuration 1.3//EN",
          "http://www.puppycrawl.com/dtds/configuration_1_3.dtd"
        ),
        Nil
      )
    )

    val checkstyleArgs = Array(
      "-c",
      configFile, // checkstyle configuration file
      javaSource.path.toString, // location of Java source file
      "-f",
      "xml", // output format
      "-o",
      outputLocation.toString // output file
    )

    Jvm.runSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = checkstyleClasspath().map(_.path),
      mainArgs = checkstyleArgs,
      workingDir = T.dest
    )

    xsltTransformations match {
      case None => // Nothing to do
      case Some(xslt) => applyXSLT(outputLocation.toIO, xslt)
    }

    if (outputLocation.toIO.exists && severityLevel.isDefined) {
      val log = T.log
      val issuesFound = processIssues(log, outputLocation, severityLevel.get)

      if (issuesFound > 0) {
        log.error(issuesFound + " issue(s) found in Checkstyle report: " + outputLocation + "")
        sys.exit(1)
      }
    }
  }

  /**
   * Processes style issues found by Checkstyle, returning a count of the number of issues
   *
   * @param log The Mill Logger
   * @param outputLocation The location of the Checkstyle report
   * @param severityLevel The severity level at which to fail the build if style issues exist at that level
   * @return A count of the total number of issues processed
   */
  private def processIssues(
      log: Logger,
      outputLocation: Path,
      severityLevel: CheckstyleSeverityLevel
  ): Int = {
    val report = scala.xml.XML.loadFile(outputLocation.toIO)
    val checkstyleSeverityLevelIndex = CheckstyleSeverityLevel.values.toArray.indexOf(severityLevel)
    val appliedCheckstyleSeverityLevels =
      CheckstyleSeverityLevel.values.drop(checkstyleSeverityLevelIndex)

    (report \ "file").flatMap { file =>
      (file \ "error").map { error =>
        val severity = CheckstyleSeverityLevel.withName(error.attribute("severity").get.head.text)
        appliedCheckstyleSeverityLevels.contains(severity) match {
          case false => 0
          case true =>
            val lineNumber = error.attribute("line").get.head.text
            val filename = file.attribute("name").get.head.text
            val errorMessage = error.attribute("message").get.head.text
            log.error(
              "Checkstyle " + severity + " found in " + filename + ":" + lineNumber + ": " + errorMessage
            )
            1
        }
      }
    }.sum
  }

  /**
   * Applies a set of XSLT transformation to the XML file produced by checkstyle
   *
   * @param input The XML file produced by checkstyle
   * @param transformations The XSLT transformations to be applied
   */
  private def applyXSLT(input: File, transformations: Set[CheckstyleXSLTSettings]): Unit = {
    val processor = new Processor(false)
    val source = processor.newDocumentBuilder().build(input)

    transformations foreach { transform: CheckstyleXSLTSettings =>
      val output = processor.newSerializer(transform.output)
      val compiler = processor.newXsltCompiler()
      val executor = compiler.compile(new StreamSource(transform.xslt))
      val transformer = executor.load()
      transformer.setInitialContextNode(source)
      transformer.setDestination(output)
      transformer.transform()
      transformer.close()
      output.close()
    }
  }
}
