package mill.contrib.checkstyle

import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel.CheckstyleSeverityLevel
import com.etsy.sbt.checkstyle.{
  CheckstyleConfigLocation,
  CheckstyleSeverityLevel,
  CheckstyleXSLTSettings
}
import mill.api.Logger
import mill.{Agg, T}
import mill.scalalib.{Dep, DepSyntax, JavaModule}
import mill.util.Jvm
import net.sf.saxon.s9api.Processor
import os.Path

import java.io.File
import javax.xml.transform.stream.StreamSource
import scala.xml.{Elem, SAXParser}
import scala.xml.factory.XMLLoader

/**
 * Integrate Checkstyle into a [[JavaModule]].
 *
 * See https://checkstyle.sourceforge.io/
 */
trait CheckstyleModule extends JavaModule {

  /** The `checkstyle` version to use. Defaults to `10.18.1`. */
  def checkstyleVersion: T[String] = T.input { "10.18.1" }

  /** The location of the `checkstyle` configuration file */
  def checkstyleConfigLocation: T[CheckstyleConfigLocation] = T.input {
    CheckstyleConfigLocation.File((T.workspace / "checkstyle-config.xml").toString())
  }

  /** An optional set of XSLT transformations to be applied to the checkstyle output */
  def checkstyleXsltTransformations: T[Option[Set[CheckstyleXSLTSettings]]] = T.input { None }

  /** The severity level which should fail the build */
  def checkstyleSeverityLevel: Option[CheckstyleSeverityLevel] = None

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
   * @param outputFile The Checkstyle report output path.
   */
  def checkstyle = T {
    val outputLocation = T.dest / "checkstyle-report.xml"
    val targetFolder = T.dest.toIO
    val configFile = targetFolder + "/checkstyle-config.xml"
    targetFolder.mkdirs()

    object XML extends XMLLoader[Elem] {
      override def parser: SAXParser = {
        val f = javax.xml.parsers.SAXParserFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.newSAXParser()
      }
    }

    val config =
      XML.loadString(checkstyleConfigLocation().read(compileClasspath().map(_.path).toSeq))
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
      "-f",
      "xml", // output format
      "-o",
      outputLocation.toString() // output file
    ) ++ sources().map(_.path.toString) // location of Java source files

    Jvm.runSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = checkstyleClasspath().map(_.path),
      mainArgs = checkstyleArgs,
      workingDir = T.dest
    )

    checkstyleXsltTransformations() match {
      case None => // Nothing to do
      case Some(xslt) => applyXSLT(outputLocation.toIO, xslt)
    }

    checkstyleSeverityLevel.foreach { severityLevel =>
      if (outputLocation.toIO.exists) {
        val log = T.log
        val issuesFound = processIssues(log, outputLocation, severityLevel)

        if (issuesFound > 0) {
          log.error(issuesFound + " issue(s) found in Checkstyle report: " + outputLocation + "")
          throw new RuntimeException("Checkstyle issues found")
        }
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
