package mill.spotless

import com.diffplug.spotless.generic.LicenseHeaderStep
import com.diffplug.spotless.java.{
  FormatAnnotationsStep,
  GoogleJavaFormatStep,
  PalantirJavaFormatStep
}
import com.diffplug.spotless.kotlin.{DiktatStep, KtLintStep, KtfmtStep}
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
      val _header = header.getOrElse(headerFile
        .flatMap(bytes(_)).fold(throw new Exception(s"$headerFile not found"))(
          new String(_, ctx.encoding)
        ))
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
      DiktatStep.create(
        version,
        ctx,
        isScript,
        signature(configFile)
      )
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
        signature(configFile),
        Collections.emptyMap(),
        customRuleSets.asJava
      )
  }

  private def bytes(rel: os.RelPath)(using res: PathResolver) =
    res.path(rel).map(os.read.bytes)

  private def signature(rel: os.RelPath)(using res: PathResolver) =
    res.path(rel).fold(null)(path => FileSignature.signAsList(path.toIO))

  private given Reader[os.RelPath] = reader[String].map(os.RelPath(_))

  private given Reader[LicenseHeaderStep.YearMode] =
    reader[String].map(LicenseHeaderStep.YearMode.valueOf)

  private given Reader[KtfmtStep.Style] = reader[String].map(KtfmtStep.Style.valueOf)
}
