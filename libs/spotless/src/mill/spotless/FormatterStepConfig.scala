package mill.spotless

import com.diffplug.spotless.generic.{FenceStep, LicenseHeaderStep}
import com.diffplug.spotless.java.{FormatAnnotationsStep, PalantirJavaFormatStep}
import com.diffplug.spotless.kotlin.{KtLintStep, KtfmtStep}
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.{FileSignature, FormatterStep}
import upickle.default.*

import scala.jdk.CollectionConverters.*

/**
 * Configuration used to `build` a
 * [[https://github.com/diffplug/spotless?tab=readme-ov-file#current-feature-matrix FormatterStep]].
 */
sealed trait FormatterStepConfig derives Reader {
  def build(using FormatterContext): FormatterStep
}

object FormatterStepConfig {

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/PalantirJavaFormatStep.java PalantirJavaFormatStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#palantir-java-format info]]
   */
  case class PalantirJavaFormat(
      version: Option[String] = None,
      style: Option[String] = None,
      formatJavadoc: Boolean = PalantirJavaFormatStep.defaultFormatJavadoc()
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: FormatterContext) =
      PalantirJavaFormatStep.create(
        version.getOrElse(PalantirJavaFormatStep.defaultVersion()),
        style.getOrElse(PalantirJavaFormatStep.defaultStyle()),
        formatJavadoc,
        ctx.provisioner
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/FormatAnnotationsStep.java FormatAnnotationsStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#formatAnnotations info]]
   */
  case class FormatAnnotations(
      addedTypeAnnotations: Seq[String] = Seq(),
      removedTypeAnnotations: Seq[String] = Seq()
  ) extends FormatterStepConfig derives Reader {
    def build(using FormatterContext) =
      FormatAnnotationsStep.create(
        addedTypeAnnotations.asJava,
        removedTypeAnnotations.asJava
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtfmtStep.java KtfmtStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktfmt info]]
   */
  case class Ktfmt(
      version: Option[String] = None,
      style: Option[KtfmtStep.Style] = None,
      maxWidth: Option[Int] = None,
      blockIndent: Option[Int] = None,
      continuationIndent: Option[Int] = None,
      removeUnusedImports: Option[Boolean] = None,
      manageTrailingCommas: Option[Boolean] = None
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: FormatterContext) =
      val options = new KtfmtStep.KtfmtFormattingOptions(
        maxWidth.fold(null)(Int.box),
        blockIndent.fold(null)(Int.box),
        continuationIndent.fold(null)(Int.box),
        removeUnusedImports.fold(null)(Boolean.box),
        manageTrailingCommas.fold(null)(Boolean.box)
      )
      KtfmtStep.create(
        version.getOrElse(KtfmtStep.defaultVersion()),
        ctx.provisioner,
        style.orNull,
        options
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtLintStep.java KtLintStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktlint info]]
   */
  case class KtLint(
      version: Option[String] = None,
      configFile: os.RelPath = ".editorconfig",
      customRuleSets: Seq[String] = Nil
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: FormatterContext) =
      KtLintStep.create(
        version.getOrElse(KtLintStep.defaultVersion()),
        ctx.provisioner,
        fileSigOrNull(configFile),
        Map.empty.asJava,
        customRuleSets.asJava
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/scala/ScalaFmtStep.java ScalaFmtStep]].
   * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#scalafmt info]]
   */
  case class ScalaFmt(
      version: Option[String] = None,
      scalaMajorVersion: Option[String] = None,
      configFile: os.RelPath = ".scalafmt.conf"
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: FormatterContext) =
      ScalaFmtStep.create(
        version.getOrElse(ScalaFmtStep.defaultVersion()),
        scalaMajorVersion.orNull,
        ctx.provisioner,
        fileOrNull(configFile)
      )
  }

  /**
   * Configuration for [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/FenceStep.java FenceStep]] that is used to apply a
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#spotlessoff-and-spotlesson toggle]]
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#inception-languages-within-languages-within withinBlock]]
   */
  case class Fence(
      steps: Seq[FormatterStepConfig],
      name: String,
      regex: Seq[String],
      preserve: Boolean
  ) extends FormatterStepConfig derives Reader {
    require(steps.nonEmpty, "steps must be non-empty")
    def build(using FormatterContext): FormatterStep =
      var step = FenceStep.named(name)
      step = regex match {
        case Seq(regex) => step.regex(regex)
        case Seq(open, close) => step.openClose(open, close)
        case seq => throw Exception(s"invalid regex $seq")
      }
      val steps = this.steps.map(_.build).asJava
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
    def build(using ctx: FormatterContext) =
      val content = header
        .getOrElse(String(bytesOrThrow(headerFile), ctx.encoding))
      var step = LicenseHeaderStep.headerDelimiter(content, delimiter)
      step = name.fold(step)(step.withName)
      step = contentPattern.fold(step)(step.withContentPattern)
      step = yearSeparator.fold(step)(step.withYearSeparator)
      step = yearMode.fold(step)(step.withYearMode)
      step = skipLinesMatching.fold(step)(step.withSkipLinesMatching)
      step.build()
  }

  private def bytesOrThrow(rel: os.RelPath)(using ctx: FormatterContext) =
    ctx.resolver.path(rel).fold(throw new Exception(s"$rel not found"))(os.read.bytes(_))

  private def fileOrNull(rel: os.RelPath)(using ctx: FormatterContext) =
    ctx.resolver.path(rel).fold(null)(_.toIO)

  private def fileSigOrNull(res: os.RelPath)(using ctx: FormatterContext) =
    ctx.resolver.path(res).fold(null)(path => FileSignature.signAsList(path.toIO))
}

private given Reader[os.RelPath] = reader[String].map(os.RelPath(_))

private given Reader[LicenseHeaderStep.YearMode] =
  reader[String].map(LicenseHeaderStep.YearMode.valueOf)

private given Reader[KtfmtStep.Style] = reader[String].map(KtfmtStep.Style.valueOf)
