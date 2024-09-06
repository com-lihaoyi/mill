package com.etsy.sbt.checkstyle

import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel._
import mill.T
import mill.util.Jvm

import javax.xml.transform.stream.StreamSource

/**
 * A Scala wrapper around the Checkstyle Java API
 *
 * @author Andrew Johnson <ajohnson@etsy.com>
 * @author Joseph Earl <joe@josephearl.co.uk>
 */
object Checkstyle {
  /**
   * Runs checkstyle
   *
   * @param javaSource The Java source path.
   * @param outputFile The Checkstyle report output path.
   * @param configLocation The Checkstyle config location.
   * @param xsltTransformations XSLT transformations to apply.
   * @param severityLevel The severity level used to fail the build.
   */
  def checkstyle(javaSource: File, resources: Seq[File], outputFile: File, configLocation: CheckstyleConfigLocation,
                 xsltTransformations: Option[Set[CheckstyleXSLTSettings]], severityLevel: Option[CheckstyleSeverityLevel], streams: TaskStreams): Unit = {
    val outputLocation = outputFile.absolutePath
    val targetFolder = outputFile.getParentFile
    val configFile = targetFolder + "/checkstyle-config.xml"

    targetFolder.mkdirs()

    val config = scala.xml.XML.loadString(configLocation.read(resources))
    scala.xml.XML.save(configFile, config, "UTF-8", xmlDecl = true,
      scala.xml.dtd.DocType("module", scala.xml.dtd.PublicID("-//Puppy Crawl//DTD Check Configuration 1.3//EN",
        "http://www.puppycrawl.com/dtds/configuration_1_3.dtd"), Nil))

    val checkstyleArgs = Array(
      "-c", configFile, // checkstyle configuration file
      javaSource.absolutePath, // location of Java source file
      "-f", "xml", // output format
      "-o", outputLocation // output file
    )


    Jvm.runSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = null, //TODO
      mainArgs = checkstyleArgs,
      workingDir = T.dest
    )

    xsltTransformations match {
      case None => // Nothing to do
      case Some(xslt) => applyXSLT(file(outputLocation), xslt)
    }

    if (file(outputLocation).exists && severityLevel.isDefined) {
      val log = streams.log
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
   * @param log The SBT Logger
   * @param outputLocation The location of the Checkstyle report
   * @param severityLevel The severity level at which to fail the build if style issues exist at that level
   * @return A count of the total number of issues processed
   */
  private def processIssues(log: Logger, outputLocation: String, severityLevel: CheckstyleSeverityLevel): Int = {
    val report = scala.xml.XML.loadFile(file(outputLocation))
    val checkstyleSeverityLevelIndex = CheckstyleSeverityLevel.values.toArray.indexOf(severityLevel)
    val appliedCheckstyleSeverityLevels = CheckstyleSeverityLevel.values.drop(checkstyleSeverityLevelIndex)


    (report \ "file").flatMap { file =>
      (file \ "error").map { error =>
        val severity = CheckstyleSeverityLevel.withName(error.attribute("severity").get.head.text)
        appliedCheckstyleSeverityLevels.contains(severity) match {
          case false => 0
          case true => val lineNumber = error.attribute("line").get.head.text
            val filename = file.attribute("name").get.head.text
            val errorMessage = error.attribute("message").get.head.text
            log.error("Checkstyle " + severity + " found in " + filename + ":" + lineNumber + ": " + errorMessage)
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
