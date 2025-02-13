package mill
package javalib.spotless

import mill.api.PathRef
import mill.scalalib.{CoursierModule, DepSyntax}
import java.util.Set
import java.util.stream.Collectors
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.File
import java.io.IOException
import com.diffplug.spotless.{Provisioner, Formatter, LineEnding}
import scala.jdk.CollectionConverters._
import scala.collection.mutable

/**
 * Formats Java, Kotlin & Scala source files using [[https://github.com/diffplug/spotless Spotless]].
 *
 * Follows the 2-part design suggested here: https://github.com/diffplug/spotless/issues/527#issuecomment-588397021
 */
trait SpotlessModule extends CoursierModule {

  /**
   * Spotless settings for any of the JVM languages that are supported.
   */
  def jvmLangConfig: JVMLangConfig

  /**
   * Classpath for running any of the formatters supported by Spotless.
   */
  def jvmLangLibClasspath: T[Seq[PathRef]]

  /**
   * Formats source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   * @param sources list of file or folder path(s) to be processed
   *                - path must be relative to [[moduleDir]]
   *                - when empty, all [[sources]] are processed
   */
  def spotless(
      check: mainargs.Flag = mainargs.Flag(value = false),
      sources: mainargs.Leftover[String]
  ): Command[Unit] = Task.Command {

    val settings = jvmLangConfig

    val steps = settings.getSteps(
      PathRef(moduleDir).path,
      new Provisioner {
        def provisionWithTransitives(
            withTransitives: Boolean,
            mavenCoordinates: java.util.Collection[String]
        ): java.util.Set[java.io.File] = {
          SpotlessModule.provisionWithTransitives(withTransitives, jvmLangLibClasspath())
        }
      }
    )

    val _sources =
      if (sources.value.isEmpty) Seq(PathRef(moduleDir))
      else sources.value.iterator.map(rel => PathRef(moduleDir / os.RelPath(rel)))

    val formatter = Formatter.builder()
      .encoding(StandardCharsets.UTF_8)
      .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
      .steps(steps)
      .build()

    val results = _sources.iterator.map { folder =>
      process(check.value, formatter, folder.path.toIO, settings.target)
    }
    results.iterator.foreach(identity)
  }

  case class FormatViolation(path: Path, diff: String)

  def process(
      isCheckMode: Boolean,
      formatter: Formatter,
      sourceDir: File,
      extension: String
  ): Unit = {
    val paths: Set[java.nio.file.Path] = getSourceFiles(sourceDir, extension)

    if (isCheckMode) {
      val violations = scala.collection.mutable.ListBuffer[FormatViolation]()
      paths.forEach(path => checkFile(formatter, path, violations))

      if (violations.nonEmpty) {
        println("The following files had format violations:")

        violations.foreach { violation =>
          println(s"    ${violation.path}")
          if (sys.env.get("VERBOSE").contains("1")) {
            val formattedDiff = violation.diff.linesIterator
              .map(line => s"        $line")
              .mkString("\n")
            println(formattedDiff)
          }
        }

        println(s"Run 'mill spotless $sourceDir' to fix these violations.\n")
      } else {
        println("Verification completed. No format violations found!")
      }
    } else {
      paths.forEach(path => formatFile(formatter, path))
      println("Formatting completed successfully!")
    }
  }

  private def getSourceFiles(sourceDir: File, extension: String): Set[Path] = {
    Files.walk(Paths.get(sourceDir.getAbsolutePath))
      .filter(Files.isRegularFile(_))
      .filter(_.toString.endsWith(extension))
      .collect(Collectors.toSet())
  }

  private def checkFile(
      formatter: Formatter,
      path: Path,
      violations: mutable.ListBuffer[FormatViolation]
  ): Unit = {
    try {
      val file = path.toFile()
      val content = new String(Files.readAllBytes(path))
      val formatted = formatter.compute(content, file)

      if (content != formatted) {
        violations += FormatViolation(path, generateUnifiedDiff(content, formatted))
      }
    } catch {
      case e: IOException =>
        println(s"[Check] Error processing file $path: ${e.getMessage}")
        e.printStackTrace()
      case e: Exception =>
        println(s"[Check] Formatting error in file $path: ${e.getMessage}")
        e.printStackTrace()
    }
  }

  private def formatFile(formatter: Formatter, path: Path): Unit = {
    try {
      val file = path.toFile()
      val content = new String(Files.readAllBytes(path))
      val formatted = formatter.compute(content, file)

      if (content != formatted) {
        Files.write(path, formatted.getBytes())
        println(s"Formatted: $path")
      }
    } catch {
      case e: IOException =>
        println(s"[Apply] Error processing file $path: ${e.getMessage}")
        e.printStackTrace()
      case e: Exception =>
        println(s"[Apply] Formatting error in file $path: ${e.getMessage}")
        e.printStackTrace()
    }
  }

  private[spotless] def generateUnifiedDiff(original: String, formatted: String): String = {
    // Split into lines, both the original file and its formatted counterpart
    val originalLines = original.split("\n", -1)
    val formattedLines = formatted.split("\n", -1)

    val diff = new StringBuilder()
    diff.append(s"@@ -1,${originalLines.length} +1,${formattedLines.length} @@\n")

    // Add diff markers and make whitespace easier to visualize
    originalLines.foreach(line => diff.append("-").append(line.replace(" ", "·")).append("\n"))
    formattedLines.foreach(line => diff.append("+").append(line.replace(" ", "·")).append("\n"))

    diff.toString()
  }
}

object SpotlessModule {

  /**
   * Resolves classpath requests for all 3rd-party dependencies used by the Spotless library.
   */
  def provisionWithTransitives(withTransitives: Boolean, classpath: Seq[PathRef]): Set[File] = {
    val deps: Seq[PathRef] = classpath
    val files = deps.map(pathRef => pathRef.path.toIO.toPath.toFile).toSet.asJava
    files
  }
}
