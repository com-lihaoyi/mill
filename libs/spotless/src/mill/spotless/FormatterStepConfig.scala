package mill.spotless

import com.diffplug.spotless.generic.{FenceStep, LicenseHeaderStep}
import com.diffplug.spotless.java.{FormatAnnotationsStep, PalantirJavaFormatStep}
import com.diffplug.spotless.kotlin.{KtLintStep, KtfmtStep}
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.{FileSignature, FormatterStep}
import upickle.default.*

import java.nio.charset.Charset
import scala.jdk.CollectionConverters.*

/**
 * Configuration used to `build` a
 * [[https://github.com/diffplug/spotless?tab=readme-ov-file#current-feature-matrix FormatterStep]].
 */
sealed trait FormatterStepConfig derives Reader {
  def build(encoding: Charset)(using SpotlessContext): FormatterStep
}

object FormatterStepConfig {

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/FenceStep.java FenceStep]] that is used to apply a
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#spotlessoff-and-spotlesson toggle]]
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#inception-languages-within-languages-within withinBlock]]
   */
  case class Fence(
      steps: Seq[FormatterStepConfig],
      name: String,
      regex: Seq[String],
      preserve: Boolean = true
  ) extends FormatterStepConfig derives Reader {
    require(steps.nonEmpty, "steps must be non-empty")
    def build(encoding: Charset)(using SpotlessContext): FormatterStep =
      var step = FenceStep.named(name)
      step = regex match {
        case Seq(regex) => step.regex(regex)
        case Seq(open, close) => step.openClose(open, close)
        case seq => throw Exception(s"invalid regex $seq")
      }
      val steps = this.steps.map(_.build(encoding)).asJava
      if preserve then step.preserveWithin(steps) else step.applyWithin(steps)
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/LicenseHeaderStep.java LicenseHeaderStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#license-header info]]
   */
  case class LicenseHeader(
      header: Option[String] = None,
      headerFile: os.RelPath = "LICENSE",
      delimiter: String = LicenseHeaderStep.DEFAULT_JAVA_HEADER_DELIMITER,
      name: Option[String] = None,
      contentPattern: Option[String] = None,
      yearSeparator: Option[String] = None,
      yearMode: Option[LicenseHeaderStep.YearMode] = None,
      skipLinesMatching: Option[String] = None
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using ctx: SpotlessContext) =
      val content = header.getOrElse(String(
        os.read.bytes(ctx.path(headerFile).getOrElse(throw Exception(s"$headerFile not found"))),
        encoding
      ))
      var step = LicenseHeaderStep.headerDelimiter(content, delimiter)
      step = name.fold(step)(step.withName)
      step = contentPattern.fold(step)(step.withContentPattern)
      step = yearSeparator.fold(step)(step.withYearSeparator)
      step = yearMode.fold(step)(step.withYearMode)
      step = skipLinesMatching.fold(step)(step.withSkipLinesMatching)
      step.build()
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/FormatAnnotationsStep.java FormatAnnotationsStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#formatAnnotations info]]
   */
  case class FormatAnnotations(
      addedTypeAnnotations: Seq[String] = Seq(),
      removedTypeAnnotations: Seq[String] = Seq()
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using SpotlessContext) =
      FormatAnnotationsStep.create(
        addedTypeAnnotations.asJava,
        removedTypeAnnotations.asJava
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/PalantirJavaFormatStep.java PalantirJavaFormatStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#palantir-java-format info]]
   */
  case class PalantirJavaFormat(
      version: String = PalantirJavaFormatStep.defaultVersion(),
      style: String = PalantirJavaFormatStep.defaultStyle(),
      formatJavadoc: Boolean = PalantirJavaFormatStep.defaultFormatJavadoc()
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using ctx: SpotlessContext) =
      PalantirJavaFormatStep.create(
        version,
        style,
        formatJavadoc,
        ctx.provisioner
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtLintStep.java KtLintStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktlint info]]
   */
  case class KtLint(
      version: String = KtLintStep.defaultVersion(),
      configFile: os.RelPath = ".editorconfig",
      customRuleSets: Seq[String] = Nil
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using ctx: SpotlessContext) =
      val signature = ctx.path(configFile).fold(null)(p => FileSignature.signAsList(p.toIO))
      KtLintStep.create(
        version,
        ctx.provisioner,
        signature,
        Map.empty[String, String].asJava,
        customRuleSets.asJava
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtfmtStep.java KtfmtStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktfmt info]]
   */
  case class Ktfmt(
      version: String = KtfmtStep.defaultVersion(),
      style: Option[KtfmtStep.Style] = None,
      maxWidth: Option[Int] = None,
      blockIndent: Option[Int] = None,
      continuationIndent: Option[Int] = None,
      removeUnusedImports: Option[Boolean] = None,
      manageTrailingCommas: Option[Boolean] = None
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using ctx: SpotlessContext) =
      val options = new KtfmtStep.KtfmtFormattingOptions(
        maxWidth.fold(null)(Int.box),
        blockIndent.fold(null)(Int.box),
        continuationIndent.fold(null)(Int.box),
        removeUnusedImports.fold(null)(Boolean.box),
        manageTrailingCommas.fold(null)(Boolean.box)
      )
      KtfmtStep.create(
        version,
        ctx.provisioner,
        style.orNull,
        options
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/scala/ScalaFmtStep.java ScalaFmtStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#scalafmt info]]
   */
  case class ScalaFmt(
      version: String = ScalaFmtStep.defaultVersion(),
      scalaMajorVersion: String = ScalaFmtStep.defaultScalaMajorVersion(),
      configFile: os.RelPath = ".scalafmt.conf"
  ) extends FormatterStepConfig derives Reader {
    def build(encoding: Charset)(using ctx: SpotlessContext) =
      val file = ctx.path(configFile).fold(null)(_.toIO)
      ScalaFmtStep.create(
        version,
        scalaMajorVersion,
        ctx.provisioner,
        file
      )
  }
}

private given Reader[os.RelPath] = reader[String].map(os.RelPath(_))

private given Reader[LicenseHeaderStep.YearMode] =
  reader[String].map(LicenseHeaderStep.YearMode.valueOf)

private given Reader[KtfmtStep.Style] = reader[String].map(KtfmtStep.Style.valueOf)
