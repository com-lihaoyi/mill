package mill.scalalib.spotless

import mill.define.{BuildCtx, PathRef}
import upickle.core.Visitor
import upickle.default.*
import upickle.implicits.serializeDefaults

import scala.util.Using

/**
 * Configuration for building a [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/Formatter.java Spotless formatter]].
 * @param steps format steps to apply
 * @param includes [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) path matchers]] for files to format
 * @param excludes [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) path matchers]] for files not to format
 * @param lineEnding name of file [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/LineEnding.java line endings]]
 * @param encoding name of file encoding charset
 * @param suppressions lints to suppress
 */
@mill.api.experimental
case class Format(
    steps: Seq[Format.Step],
    includes: Seq[String],
    excludes: Seq[String] = Seq(),
    lineEnding: String = "GIT_ATTRIBUTES_FAST_ALLSAME",
    encoding: String = "UTF-8",
    suppressions: Seq[Format.Suppress] = Seq()
) derives ReadWriter
@mill.api.experimental
object Format {

  def apply(includes: String*)(steps: Step*): Format = apply(steps, includes)

  def defaults: Seq[Format] = Seq(
    apply("glob:**.java")(PalantirJavaFormat()),
    apply("glob:**.{kt,kts}")(Ktfmt()),
    apply("glob:**.scala")(ScalaFmt())
  )

  def readAll(formatsFile: os.Path): Seq[Format] =
    Using.resource(os.read.inputStream(formatsFile))(read[Seq[Format]](_))

  /**
   * Configuration for creating a [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/FormatterStep.java formatter step]]
   */
  sealed trait Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/EndWithNewlineStep.java]]
   */
  case class EndWithNewline() extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/FenceStep.java]]
   */
  case class Fence(name: String, open: String, close: String) derives ReadWriter
  object Fence {

    /**
     * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#inception-languages-within-languages-within]]
     */
    case class ApplyWithin(fence: Fence, steps: Seq[Step]) extends Step derives ReadWriter

    /**
     * @see [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#spotlessoff-and-spotlesson]]
     */
    case class PreserveWithin(steps: Seq[Step], fence: Fence = null) extends Step derives ReadWriter
  }

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/IndentStep.java]]
   */
  case class Indent(`type`: String = "TAB", numSpacesPerTab: Int = 4) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/Jsr223Step.java]]
   */
  case class Jsr223(name: String, dependency: String, engine: String, script: ContentOrFile)
      extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/LicenseHeaderStep.java]]
   */
  case class LicenseHeader(
      delimiter: String,
      @serializeDefaults(true) header: ContentOrFile =
        ContentOrFile(file = Some(SubPathRef("LICENSE"))),
      name: String = null,
      contentPattern: String = null,
      yearSeparator: String = "-",
      yearMode: String = "PRESERVE",
      skipLinesMatching: String = null
  ) extends Step derives Reader

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/NativeCmdStep.java]]
   */
  case class NativeCmd(name: String, pathToExe: String, arguments: Seq[String] = Seq()) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/ReplaceRegexStep.java]]
   */
  case class ReplaceRegex(name: String, regex: String, replacement: String) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/ReplaceStep.java]]
   */
  case class Replace(name: String, target: String, replacement: String) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/TrimTrailingWhitespaceStep.java]]
   */
  case class TrimTrailingWhitespace() extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/CleanthatJavaStep.java]]
   */
  case class CleanthatJava(
      groupArtifact: String = null,
      version: String = null,
      sourceJdkVersion: String = null,
      mutators: Seq[String] = Seq("SafeAndConsensual"),
      excludedMutators: Seq[String] = Seq(),
      includeDraft: Boolean = false
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/FormatAnnotationsStep.java]]
   */
  case class FormatAnnotations(
      addedTypeAnnotations: Seq[String] = Seq(),
      removedTypeAnnotations: Seq[String] = Seq()
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/GoogleJavaFormatStep.java]]
   */
  case class GoogleJavaFormat(
      groupArtifact: String = null,
      version: String = null,
      style: String = "GOOGLE",
      reflowLongStrings: Boolean = false,
      reorderImports: Boolean = false,
      formatJavadoc: Boolean = true
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/ImportOrderStep.java]]
   */
  case class ImportOrder(
      importOrder: Seq[String],
      wildcardsLast: Boolean = false,
      semanticSort: Boolean = false,
      treatAsPackage: Set[String] = Set(),
      treatAsClass: Set[String] = Set(),
      forJava: Boolean = true
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/PalantirJavaFormatStep.java]]
   */
  case class PalantirJavaFormat(
      version: String = null,
      style: String = "PALANTIR",
      formatJavadoc: Boolean = false
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/RemoveUnusedImportsStep.java]]
   */
  case class RemoveUnusedImports(remover: String = "google-java-format") extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/DiktatStep.java]]
   */
  case class Diktat(
      version: String = null,
      isScript: Boolean = false,
      @serializeDefaults(true) configFile: SubPathRef =
        SubPathRef("diktat-analysis.yml")
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtfmtStep.java]]
   */
  case class Ktfmt(
      version: String = null,
      style: String = null,
      maxWidth: Option[Int] = None,
      blockIndent: Option[Int] = None,
      continuationIndent: Option[Int] = None,
      removeUnusedImports: Option[Boolean] = None,
      manageTrailingCommas: Option[Boolean] = None
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtLintStep.java]]
   */
  case class KtLint(
      version: String = null,
      @serializeDefaults(true) editorConfig: SubPathRef = SubPathRef(".editorconfig"),
      customRuleSets: Seq[String] = Seq()
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/scala/ScalaFmtStep.java]]
   */
  case class ScalaFmt(
      version: String = null,
      scalaMajorVersion: String = null,
      @serializeDefaults(true) configFile: SubPathRef = SubPathRef(".scalafmt.conf")
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/LintSuppression.java]].
   */
  case class Suppress(
      path: String = null,
      step: String = null,
      shortCode: String = null
  ) derives ReadWriter

  /**
   * Represents a string value that can be inlined as `content` or must be read from `file`.
   */
  case class ContentOrFile(
      content: String = null,
      @serializeDefaults(true) file: Option[SubPathRef] = None
  ) derives ReadWriter

  /**
   * A variant of [[PathRef]] created from an `os.SubPath` resolved against the workspace root.
   * This allows for usages, such as in configuration files,
   * where it is preferred to omit the machine specific path prefix.
   */
  opaque type SubPathRef <: PathRef = PathRef
  object SubPathRef {

    def apply(sub: os.SubPath): SubPathRef = PathRef(BuildCtx.workspaceRoot / sub)

    private def decode(s: String): SubPathRef =
      if s.startsWith("ref:") then upickle.default.read[PathRef](s)
      else apply(os.SubPath(s))

    given ReadWriter[SubPathRef] =
      new Visitor.MapReader[Any, String, SubPathRef](readwriter[String])
        with ReadWriter[SubPathRef] {
        def write0[V](out: Visitor[_, V], v: SubPathRef) = PathRef.jsonFormatter.write0(out, v)
        def mapNonNullsFunction(t: String) = decode(t)
      }
  }
}
