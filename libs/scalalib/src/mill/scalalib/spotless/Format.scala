package mill.scalalib.spotless

import mill.define.{BuildCtx, PathRef, TaskCtx}
import upickle.core.Visitor
import upickle.default.*

import scala.util.{DynamicVariable, Using}

/**
 * Configuration for building a [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/Formatter.java Spotless formatter]].
 * @param steps format steps to apply
 * @param includes [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) path matchers]] for files to format
 * @param excludes [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) path matchers]] for files not to format
 * @param lineEnding name of file [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#line-endings-and-encodings-invisible-stuff line endings]]
 * @param encoding name of file [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#line-endings-and-encodings-invisible-stuff encoding charset]]
 * @param suppressions lints to suppress
 */
@mill.api.experimental // see notes in package object
case class Format(
    steps: Seq[Format.Step],
    includes: Seq[String] = Seq("glob:**"),
    excludes: Seq[String] = Seq(),
    lineEnding: String = "GIT_ATTRIBUTES_FAST_ALLSAME",
    encoding: String = "UTF-8",
    suppressions: Seq[Format.Suppress] = Seq()
) derives ReadWriter
@mill.api.experimental // see notes in package object
object Format {

  def apply(includes: String*)(steps: Step*): Format = apply(steps, includes)

  def defaults(using ctx: TaskCtx.Workspace): Seq[Format] =
    SubPathRef.dynamicWorkspace.withValue(ctx.workspace) {
      Seq(
        apply("glob:**.java")(PalantirJavaFormat()),
        apply("glob:**.{kt,kts}")(Ktfmt()),
        apply("glob:**.{scala,sc,mill}")(ScalaFmt())
      )
    }

  def readAll(formatsFile: os.Path)(using ctx: TaskCtx.Workspace): Seq[Format] =
    BuildCtx.withFilesystemCheckerDisabled {
      SubPathRef.dynamicWorkspace.withValue(ctx.workspace) {
        Using.resource(os.read.inputStream(formatsFile))(read(_))
      }
    }

  /**
   * Configuration for creating a [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/FormatterStep.java formatter step]]
   */
  sealed trait Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/EndWithNewlineStep.java]]
   */
  case class EndWithNewline() extends Step derives ReadWriter

  /**
   * Identifies blocks using `pattern`, specified as a single regular expression or a pair of open/close markers.
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/FenceStep.java]]
   */
  case class Fence(name: String, pattern: Seq[String]) derives ReadWriter

  /**
   * Applies `steps` outside `fence`. When `fence` is `null`, the default
   * [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#spotlessoff-and-spotlesson toggle]] is used.
   */
  case class FenceToggle(steps: Seq[Step], fence: Fence = null) extends Step derives ReadWriter

  /**
   * Applies `steps` [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#inception-languages-within-languages-within inside]] `fence`.
   */
  case class FenceWithin(fence: Fence, steps: Seq[Step]) extends Step derives ReadWriter

  /**
   * Checks for consistent indentation characters.
   * @param type `TAB` | `SPACE`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/IndentStep.java]]
   */
  case class Indent(`type`: String = "TAB", numSpacesPerTab: Option[Int] = None) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/Jsr223Step.java]]
   */
  case class Jsr223(name: String, dependency: String, engine: String, script: ContentOrFile)
      extends Step
      derives ReadWriter

  /**
   * Prefixes a license header in a file.
   * @param header header lines
   * @param delimiter regular expression identifying the first line after header
   * @param name step name
   * @param yearMode `PRESERVE` | `UPDATE_TO_TODAY` | `SET_FROM_GIT`
   * @param skipLinesMatching regular expression identifying lines before the header
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/LicenseHeaderStep.java]]
   */
  case class LicenseHeader(
      header: ContentOrFile = ContentOrFile(file = "LICENSE"),
      delimiter: String = "(package|import|public|class|module) ",
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
      importOrder: Seq[String] = Seq(),
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
  case class RemoveUnusedImports(formatter: Option[String] = None) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/DiktatStep.java]]
   */
  case class Diktat(
      version: String = null,
      isScript: Boolean = false,
      configFile: SubPathRef = SubPathRef("diktat-analysis.yml")
  ) extends Step derives ReadWriter

  /**
   * @param style `DEFAULT` | `META_FORMAT` | `DROPBOX_FORMAT` | `GOOGLE_FORMAT` | `KOTLINLANG_FORMAT`
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
      configFile: SubPathRef = SubPathRef(".editorconfig"),
      customRuleSets: Seq[String] = Seq()
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/scala/ScalaFmtStep.java]]
   */
  case class ScalaFmt(
      version: String = null,
      scalaMajorVersion: String = null,
      configFile: SubPathRef = SubPathRef(".scalafmt.conf")
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
      file: SubPathRef = null
  ) derives ReadWriter

  /**
   * A path reference created from an `os.SubPath` resolved against the workspace directory.
   * This is meant for usages where it is preferred to omit the machine specific path prefix.
   */
  case class SubPathRef private (ref: PathRef) {
    @scala.annotation.nowarn("msg=unused")
    private def copy(ref: PathRef = ref) = SubPathRef(ref)
  }
  object SubPathRef {

    def apply(sub: os.SubPath): SubPathRef = apply(PathRef(dynamicWorkspace.value / sub))

    private[spotless] val dynamicWorkspace = DynamicVariable(BuildCtx.workspaceRoot)

    given Conversion[String, SubPathRef] = s => apply(os.SubPath(s))

    given ReadWriter[SubPathRef] =
      new Visitor.MapReader[Any, String, SubPathRef](readwriter[String])
        with ReadWriter[SubPathRef] {
        def write0[V](out: Visitor[_, V], v: SubPathRef) =
          summon[ReadWriter[PathRef]].write0(out, v.ref)
        def mapNonNullsFunction(t: String) =
          if t.startsWith("ref:") then SubPathRef(upickle.default.read[PathRef](t))
          else SubPathRef(os.SubPath(t))
      }
  }
}
