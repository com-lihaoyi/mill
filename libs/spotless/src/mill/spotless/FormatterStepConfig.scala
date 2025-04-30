package mill.spotless

import com.diffplug.spotless.generic.LicenseHeaderStep
import com.diffplug.spotless.java.{
  FormatAnnotationsStep,
  GoogleJavaFormatStep,
  PalantirJavaFormatStep
}
import com.diffplug.spotless.kotlin.{DiktatStep, KtLintStep, KtfmtStep}
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.{FileSignature, FormatterStep}
import upickle.default.*

import java.util.Collections
import scala.jdk.CollectionConverters.*

/**
 * Configuration for building a [[FormatterStep]].
 */
sealed trait FormatterStepConfig derives Reader {
  def build(using SpotlessContext): FormatterStep
}

object FormatterStepConfig {

  case class FormatAnnotations(
      addedTypeAnnotations: Seq[String] = Nil,
      removedTypeAnnotations: Seq[String] = Nil
  ) extends FormatterStepConfig derives Reader {
    def build(using SpotlessContext): FormatterStep =
      FormatAnnotationsStep.create(addedTypeAnnotations.asJava, removedTypeAnnotations.asJava)
  }

  case class GoogleJavaFormat(
      groupArtifact: String = GoogleJavaFormatStep.defaultGroupArtifact(),
      version: String = GoogleJavaFormatStep.defaultVersion(),
      style: String = GoogleJavaFormatStep.defaultStyle(),
      reflowLongStrings: Boolean = GoogleJavaFormatStep.defaultReflowLongStrings(),
      reorderImports: Boolean = GoogleJavaFormatStep.defaultReorderImports(),
      formatJavadoc: Boolean = GoogleJavaFormatStep.defaultFormatJavadoc()
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep =
      GoogleJavaFormatStep.create(
        groupArtifact,
        version,
        style,
        ctx,
        reflowLongStrings,
        reorderImports,
        formatJavadoc
      )
  }

  case class PalantirJavaFormat(
      version: String = PalantirJavaFormatStep.defaultVersion(),
      style: String = PalantirJavaFormatStep.defaultStyle(),
      formatJavadoc: Boolean = PalantirJavaFormatStep.defaultFormatJavadoc()
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep =
      PalantirJavaFormatStep.create(version, style, formatJavadoc, ctx)
  }

  case class Diktat(
      version: String = DiktatStep.defaultVersionDiktat(),
      isScript: Boolean = false,
      configFile: os.RelPath = "diktat-analysis.yml"
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep =
      DiktatStep.create(version, ctx, isScript, signature(configFile).orNull)
  }

  case class Ktfmt(
      version: String = KtfmtStep.defaultVersion(),
      style: Option[KtfmtStep.Style] = None,
      maxWidth: Option[Int] = None,
      blockIndent: Option[Int] = None,
      continuationIndent: Option[Int] = None,
      removeUnusedImports: Option[Boolean] = None,
      manageTrailingCommas: Option[Boolean] = None
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep = {
      val options = new KtfmtStep.KtfmtFormattingOptions(
        maxWidth.fold(null)(Int.box),
        blockIndent.fold(null)(Int.box),
        continuationIndent.fold(null)(Int.box),
        removeUnusedImports.fold(null)(Boolean.box),
        manageTrailingCommas.fold(null)(Boolean.box)
      )
      KtfmtStep.create(version, ctx, style.orNull, options)
    }
  }

  case class KtLint(
      version: String = KtLintStep.defaultVersion(),
      configFile: os.RelPath = ".editorconfig",
      customRuleSets: Seq[String] = Nil
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep =
      KtLintStep.create(
        version,
        ctx,
        signature(configFile).orNull,
        Collections.emptyMap(),
        customRuleSets.asJava
      )
  }

  case class ScalaFmt(
      version: String = ScalaFmtStep.defaultVersion(),
      scalaMajorVersion: String = ScalaFmtStep.defaultScalaMajorVersion(),
      configFile: os.RelPath = ".scalafmt.conf"
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep =
      ScalaFmtStep.create(version, scalaMajorVersion, ctx, file(configFile).orNull)
  }

  case class LicenseHeader(
      delimiter: String,
      header: Option[String] = None,
      headerFile: Option[os.RelPath] = None,
      name: Option[String] = None,
      contentPattern: Option[String] = None,
      yearSeparator: String = LicenseHeaderStep.defaultYearDelimiter(),
      yearMode: LicenseHeaderStep.YearMode = LicenseHeaderStep.YearMode.PRESERVE,
      skipLinesMatching: Option[String] = None
  ) extends FormatterStepConfig derives Reader {
    def build(using ctx: SpotlessContext): FormatterStep = {
      val _header = header
        .orElse(headerFile.flatMap(read(_)))
        .getOrElse(throw new Exception("header|headerFile must be specified"))
      contentPattern.foldLeft(
        name.foldLeft(
          LicenseHeaderStep.headerDelimiter(_header, delimiter)
        )(_.withName(_))
      )(_.withContentPattern(_)
        .withYearSeparator(yearSeparator))
        .withYearMode(yearMode)
        .withSkipLinesMatching(skipLinesMatching.orNull)
        .build()
    }
  }

  private def read(rel: os.RelPath)(using ctx: SpotlessContext) =
    ctx.path(rel).map(path => new String(os.read.bytes(path), ctx.encoding))

  private def file(rel: os.RelPath)(using res: PathResolver) =
    res.path(rel).map(_.toIO)

  private def signature(rel: os.RelPath)(using PathResolver) =
    file(rel).map(FileSignature.signAsList(_))

  private given Reader[os.RelPath] = reader[String].map(os.RelPath(_))

  private given Reader[LicenseHeaderStep.YearMode] =
    reader[String].map(LicenseHeaderStep.YearMode.valueOf)

  private given Reader[KtfmtStep.Style] = reader[String].map(KtfmtStep.Style.valueOf)
}
