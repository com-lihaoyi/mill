package mill.javalib.spotless

import com.diffplug.spotless.generic.*
import com.diffplug.spotless.java.*
import com.diffplug.spotless.kotlin.*
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.{FileSignature, FormatterStep, LintSuppression, Provisioner}
import coursier.core.Configuration
import coursier.core.VariantSelector.ConfigurationBased
import coursier.parse.DependencyParser
import mill.api.TaskCtx
import mill.javalib.CoursierModule
import mill.javalib.spotless.Format.*
import os.Path

import java.io.File
import java.nio.charset.Charset
import scala.jdk.CollectionConverters.*

class ToFormatterStep(charset: Charset, provisioner: Provisioner)
    extends (Step => FormatterStep):

  def path(sub: RelPathRef): Option[Path] =
    Option.when(sub != null && os.exists(sub.ref.path))(sub.ref.path)

  def read(cof: ContentOrFile): String =
    Option(cof.content)
      .orElse(path(cof.file).map(path => String(os.read.bytes(path), charset)))
      .getOrElse(sys.error(s"one of content/file must be provided"))

  def signature(ref: RelPathRef): Option[FileSignature] =
    path(ref).map(path => FileSignature.signAsList(path.toIO))

  def apply(step: Step): FormatterStep = step match
    case _: EndWithNewline =>
      EndWithNewlineStep.create()
    case step: Fence =>
      import step.*
      val fence = (name, regex) match
        case (null, null | Seq()) =>
          FenceStep.named(FenceStep.defaultToggleName()).openClose(
            FenceStep.defaultToggleOff(),
            FenceStep.defaultToggleOn()
          )
        case (name, Seq(regex)) => FenceStep.named(name).regex(regex)
        case (name, Seq(open, close)) => FenceStep.named(name).openClose(open, close)
        case _ => sys.error("require name and regex for Fence")
      if preserve then fence.preserveWithin(steps.map(this).asJava)
      else fence.applyWithin(steps.map(this).asJava)
    case step: Indent =>
      import step.*
      IndentStep.create(
        IndentStep.Type.valueOf(`type`),
        numSpacesPerTab.getOrElse(IndentStep.defaultNumSpacesPerTab())
      )
    case step: Jsr223 =>
      import step.*
      Jsr223Step.create(
        name,
        dependency,
        engine,
        read(script),
        provisioner
      )
    case step: LicenseHeader =>
      import step.*
      LicenseHeaderStep.headerDelimiter(read(header), delimiter)
        .withName(name)
        .withContentPattern(contentPattern)
        .withYearSeparator(yearSeparator)
        .withYearMode(LicenseHeaderStep.YearMode.valueOf(yearMode))
        .withSkipLinesMatching(skipLinesMatching)
        .build()
    case step: NativeCmd =>
      import step.*
      NativeCmdStep.create(name, new File(pathToExe), arguments.asJava)
    case step: ReplaceRegex =>
      import step.*
      ReplaceRegexStep.create(name, regex, replacement)
    case step: Replace =>
      import step.*
      ReplaceStep.create(name, target, replacement)
    case step: TrimTrailingWhitespace =>
      TrimTrailingWhitespaceStep.create()
    case step: CleanthatJava =>
      import step.*
      CleanthatJavaStep.create(
        Option(groupArtifact).getOrElse(CleanthatJavaStep.defaultGroupArtifact()),
        Option(version).getOrElse(CleanthatJavaStep.defaultVersion()),
        Option(sourceJdkVersion).getOrElse(CleanthatJavaStep.defaultSourceJdk()),
        mutators.asJava,
        excludedMutators.asJava,
        includeDraft,
        provisioner
      )
    case step: FormatAnnotations =>
      import step.*
      FormatAnnotationsStep.create(addedTypeAnnotations.asJava, removedTypeAnnotations.asJava)
    case step: GoogleJavaFormat =>
      import step.*
      GoogleJavaFormatStep.create(
        Option(groupArtifact).getOrElse(GoogleJavaFormatStep.defaultGroupArtifact()),
        Option(version).getOrElse(GoogleJavaFormatStep.defaultVersion()),
        style,
        provisioner,
        reflowLongStrings,
        reorderImports,
        formatJavadoc
      )
    case step: ImportOrder =>
      import step.*
      val order = if forJava then ImportOrderStep.forJava() else ImportOrderStep.forGroovy()
      order.createFrom(
        wildcardsLast,
        semanticSort,
        treatAsPackage.asJava,
        treatAsClass.asJava,
        importOrder*
      )
    case step: PalantirJavaFormat =>
      import step.*
      PalantirJavaFormatStep.create(
        Option(version).getOrElse(PalantirJavaFormatStep.defaultVersion()),
        style,
        formatJavadoc,
        provisioner
      )
    case step: RemoveUnusedImports =>
      import step.*
      RemoveUnusedImportsStep.create(
        formatter.getOrElse(RemoveUnusedImportsStep.defaultFormatter()),
        provisioner
      )
    case step: Diktat =>
      import step.*
      DiktatStep.create(
        Option(version).getOrElse(DiktatStep.defaultVersionDiktat()),
        provisioner,
        isScript,
        signature(configFile).orNull
      )
    case step: Ktfmt =>
      import step.*
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
    case step: KtLint =>
      import step.*
      KtLintStep.create(
        Option(version).getOrElse(KtLintStep.defaultVersion()),
        provisioner,
        signature(configFile).orNull,
        java.util.Collections.emptyMap(),
        customRuleSets.asJava
      )
    case step: ScalaFmt =>
      import step.*
      ScalaFmtStep.create(
        Option(version).getOrElse(ScalaFmtStep.defaultVersion()),
        Option(scalaMajorVersion).getOrElse(ScalaFmtStep.defaultScalaMajorVersion()),
        provisioner,
        path(configFile).fold(null)(_.toIO)
      )

def toLintSuppression(suppress: Suppress) = {
  import suppress.*
  val ls = LintSuppression()
  if (path != null) ls.setPath(path)
  if (step != null) ls.setStep(step)
  if (shortCode != null) ls.setShortCode(shortCode)
  ls
}

def toDependencies(mavenCoordinates: java.util.Collection[String]) =
  DependencyParser.dependencies(mavenCoordinates.asScala.toSeq, "")
    .either.fold(errs => sys.error(errs.mkString(System.lineSeparator())), identity)
    .map(_.withVariantSelector(ConfigurationBased(Configuration.runtime)))

def toProvisioner(resolver: CoursierModule.Resolver)(using TaskCtx): Provisioner =
  (_, mavenCoordinates) =>
    resolver.classpath(toDependencies(mavenCoordinates)).map(_.path.toIO).toSet.asJava
