package mill
package javalib.spotless

import mill.api.{Loose, PathRef}
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
  def jvmLangLibClasspath: T[Loose.Agg[PathRef]]

  /**
   * Formats source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   * @param sources list of file or folder path(s) to be processed
   *                - path must be relative to [[millSourcePath]]
   *                - when empty, all [[sources]] are processed
   */
  def spotless(
      check: mainargs.Flag = mainargs.Flag(value = false),
      sources: mainargs.Leftover[String]
  ): Command[Unit] = Task.Command {

    val settings = jvmLangConfig

    val steps = settings.getSteps(
      PathRef(millSourcePath).path,
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
      if (sources.value.isEmpty) Seq(PathRef(millSourcePath))
      else sources.value.iterator.map(rel => PathRef(millSourcePath / os.RelPath(rel)))

    val formatter = Formatter.builder()
      .encoding(StandardCharsets.UTF_8)
      .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
      .steps(steps)
      .build()

    val results = _sources.map { folder =>
      process(check.value, formatter, folder.path.toIO, settings.target)
    }
    results.foreach(identity)
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
          val formattedDiff = violation.diff.linesIterator
            .map(line => s"        $line")
            .mkString("\n")
          println(formattedDiff)
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
  def provisionWithTransitives(withTransitives: Boolean, classpath: Agg[PathRef]): Set[File] = {
    val deps: Agg[PathRef] = classpath
    val files = deps.items.map(pathRef => pathRef.path.toIO.toPath.toFile).toSet.asJava
    files
  }
}

trait JavaSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running
   * - Google Java Format
   * - Palantir Java Format
   */
  def jvmLangLibClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(
        ivy"com.google.googlejavaformat:google-java-format:${googleJavaFormatVersion()}",
        ivy"com.palantir.javaformat:palantir-java-format:${palantirJavaFormatVersion()}"
      )
    )
  }

  /**
   * Google Java Format version. Defaults to `1.25.2`.
   */
  def googleJavaFormatVersion: T[String] = Task {
    "1.25.2"
  }

  /**
   * Google Java Format version. Defaults to `2.50.0`.
   */
  def palantirJavaFormatVersion: T[String] = Task {
    "2.50.0"
  }
}

trait KotlinSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running
   * - Ktfmt
   * - ktlint
   */
  def jvmLangLibClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(
        ivy"com.facebook:ktfmt:${ktfmtVersion()}",
        ivy"com.pinterest.ktlint:ktlint-cli:${ktlintVersion()}"
      )
    )
  }

  /**
   * Defaults to `0.53`.
   */
  def ktfmtVersion: T[String] = Task {
    "0.53"
  }

  /**
   * Defaults to `1.5.0`.
   */
  def ktlintVersion: T[String] = Task {
    "1.5.0"
  }
}

trait ScalaSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running Scala Format.
   */
  def jvmLangLibClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(ivy"org.scalameta:scalafmt-core_2.13:${scalafmtVersion()}")
    )
  }

  /**
   * Scala Format version. Defaults to `3.8.1`.
   */
  def scalafmtVersion: T[String] = Task {
    "3.8.1"
  }
}
