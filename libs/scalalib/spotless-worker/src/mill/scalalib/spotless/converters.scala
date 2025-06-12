package mill.scalalib.spotless

import com.diffplug.spotless.generic.*
import com.diffplug.spotless.java.*
import com.diffplug.spotless.kotlin.*
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.{FileSignature, FormatterStep, LintSuppression, Provisioner}
import coursier.core.Dependency
import coursier.parse.DependencyParser
import mill.define.TaskCtx
import mill.scalalib
import mill.scalalib.CoursierModule
import mill.scalalib.spotless.Format.*
import os.Path

import java.io.File
import java.nio.charset.Charset
import scala.jdk.CollectionConverters.*

class ToFormatterStep(charset: Charset, provisioner: Provisioner)
    extends (Step => FormatterStep):

  def path(sub: SubPathRef): Option[Path] =
    Option.when(null != sub && os.exists(sub.ref.path))(sub.ref.path)

  def read(cof: ContentOrFile): String =
    Option(cof.content)
      .orElse(path(cof.file).map(path => String(os.read.bytes(path), charset)))
      .getOrElse(sys.error(s"one of content/file must be provided"))

  def signature(ref: SubPathRef): Option[FileSignature] =
    path(ref).map(path => FileSignature.signAsList(path.toIO))

  def apply(format: Step): FormatterStep = format match
    case _: EndWithNewline =>
      EndWithNewlineStep.create()
    case format: FenceToggle =>
      import format.*
      toFenceStep(fence).preserveWithin(steps.map(this).asJava)
    case format: FenceWithin =>
      import format.*
      toFenceStep(fence).applyWithin(steps.map(this).asJava)
    case format: Indent =>
      import format.*
      IndentStep.create(
        IndentStep.Type.valueOf(`type`),
        numSpacesPerTab.getOrElse(IndentStep.defaultNumSpacesPerTab())
      )
    case format: Jsr223 =>
      import format.*
      Jsr223Step.create(
        name,
        dependency,
        engine,
        read(script),
        provisioner
      )
    case format: LicenseHeader =>
      import format.*
      LicenseHeaderStep.headerDelimiter(read(header), delimiter)
        .withName(name)
        .withContentPattern(contentPattern)
        .withYearSeparator(yearSeparator)
        .withYearMode(LicenseHeaderStep.YearMode.valueOf(yearMode))
        .withSkipLinesMatching(skipLinesMatching)
        .build()
    case format: NativeCmd =>
      import format.*
      NativeCmdStep.create(name, new File(pathToExe), arguments.asJava)
    case format: ReplaceRegex =>
      import format.*
      ReplaceRegexStep.create(name, regex, replacement)
    case format: Replace =>
      import format.*
      ReplaceStep.create(name, target, replacement)
    case format: TrimTrailingWhitespace =>
      TrimTrailingWhitespaceStep.create()
    case format: CleanthatJava =>
      import format.*
      CleanthatJavaStep.create(
        Option(groupArtifact).getOrElse(CleanthatJavaStep.defaultGroupArtifact()),
        Option(version).getOrElse(CleanthatJavaStep.defaultVersion()),
        Option(sourceJdkVersion).getOrElse(CleanthatJavaStep.defaultSourceJdk()),
        mutators.asJava,
        excludedMutators.asJava,
        includeDraft,
        provisioner
      )
    case format: FormatAnnotations =>
      import format.*
      FormatAnnotationsStep.create(addedTypeAnnotations.asJava, removedTypeAnnotations.asJava)
    case format: GoogleJavaFormat =>
      import format.*
      GoogleJavaFormatStep.create(
        Option(groupArtifact).getOrElse(GoogleJavaFormatStep.defaultGroupArtifact()),
        Option(version).getOrElse(GoogleJavaFormatStep.defaultVersion()),
        style,
        provisioner,
        reflowLongStrings,
        reorderImports,
        formatJavadoc
      )
    case format: ImportOrder =>
      import format.*
      val step = if forJava then ImportOrderStep.forJava() else ImportOrderStep.forGroovy()
      step.createFrom(
        wildcardsLast,
        semanticSort,
        treatAsPackage.asJava,
        treatAsClass.asJava,
        importOrder*
      )
    case format: PalantirJavaFormat =>
      import format.*
      PalantirJavaFormatStep.create(
        Option(version).getOrElse(PalantirJavaFormatStep.defaultVersion()),
        style,
        formatJavadoc,
        provisioner
      )
    case format: RemoveUnusedImports =>
      import format.*
      RemoveUnusedImportsStep.create(
        formatter.getOrElse(RemoveUnusedImportsStep.defaultFormatter()),
        provisioner
      )
    case format: Diktat =>
      import format.*
      DiktatStep.create(
        Option(version).getOrElse(DiktatStep.defaultVersionDiktat()),
        provisioner,
        isScript,
        signature(configFile).orNull
      )
    case format: Ktfmt =>
      import format.*
      KtfmtStep.create(
        Option(version).getOrElse(KtfmtStep.defaultVersion()),
        provisioner,
        Option(style).map(KtfmtStep.Style.valueOf).orNull,
        KtfmtStep.KtfmtFormattingOptions(
          maxWidth.fold(null)(Int.box),
          blockIndent.fold(null)(Int.box),
          continuationIndent.fold(null)(Int.box),
          removeUnusedImports.fold(null)(Boolean.box),
          manageTrailingCommas.fold(null)(Boolean.box)
        )
      )
    case format: KtLint =>
      import format.*
      KtLintStep.create(
        Option(version).getOrElse(KtLintStep.defaultVersion()),
        provisioner,
        signature(configFile).orNull,
        java.util.Collections.emptyMap(),
        customRuleSets.asJava
      )
    case format: ScalaFmt =>
      import format.*
      ScalaFmtStep.create(
        Option(version).getOrElse(ScalaFmtStep.defaultVersion()),
        Option(scalaMajorVersion).getOrElse(ScalaFmtStep.defaultScalaMajorVersion()),
        provisioner,
        path(configFile).fold(null)(_.toIO)
      )

def toLintSuppression(suppress: Suppress): LintSuppression = {
  import suppress.*
  val ls = LintSuppression()
  if (null != path) ls.setPath(path)
  if (null != step) ls.setStep(step)
  if (null != shortCode) ls.setShortCode(shortCode)
  ls
}

def toFenceStep(fence: Format.Fence): FenceStep =
  if null == fence then
    import FenceStep.*
    named(defaultToggleName()).openClose(defaultToggleOff(), defaultToggleOn())
  else
    import fence.*
    pattern match
      case Seq(regex) => FenceStep.named(name).regex(regex)
      case Seq(open, close, _*) => FenceStep.named(name).openClose(open, close)
      case Seq() => sys.error(s"Fence.pattern must be specified as [regex] or [open,close]")

def toDependencies(mavenCoordinates: java.util.Collection[String]): Seq[Dependency] =
  DependencyParser.dependencies(mavenCoordinates.asScala.toSeq, "")
    .either.fold(errs => sys.error(errs.mkString(", ")), identity)

def toProvisioner(resolver: CoursierModule.Resolver)(using TaskCtx): Provisioner =
  (_, mavenCoordinates) => resolver.artifacts(toDependencies(mavenCoordinates)).files.toSet.asJava
