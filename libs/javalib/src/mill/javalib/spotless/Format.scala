package mill.javalib.spotless

import mill.api.{BuildCtx, PathRef}
import upickle.core.Visitor
import upickle.default.*

import scala.util.DynamicVariable

/**
 * A specification for selecting files and the corresponding format steps to be applied.
 * @param steps Format steps to apply.
 * @param includes Path matcher patterns for files to format.
 * @param excludes Path matcher patterns for files to exclude from formatting.
 * @param lineEnding  Name of line endings in files.
 * @param encoding    Name of charset used for encoding files.
 * @param suppressions Lints to suppress.
 * @note Use with [[Format.RelPathRef.withDynamicRoot]] to configure any relative references.
 * @see
 *  - [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/Formatter.java Formatter]]
 *  - [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) Path matcher pattern]]
 *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#line-endings-and-encodings-invisible-stuff Line endings and encodings]]
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

  /**
   * @note Use with [[RelPathRef.withDynamicRoot]] to configure any relative references.
   */
  def apply(includes: String*)(steps: Step*): Format = apply(steps, includes)

  /**
   * Default [[Format]] for files with `.java` extension.
   */
  def defaultJava = ext("java")(PalantirJavaFormat())

  /**
   * Default [[Format]] for files with `.kt`, `.kts` extensions.
   */
  def defaultKotlin = ext("kt", "kts")(Ktfmt())

  /**
   * Default [[Format]] for files with `.scala`, `.sc`, `.mill` extensions.
   */
  def defaultScala = ext("scala", "sc", "mill")(ScalaFmt())

  /**
   * A [[Format]] for `steps` that selects files with the given extensions.
   */
  def ext(eh: String, et: String*)(steps: Step*) =
    apply(if et.isEmpty then s"glob:**.$eh" else et.mkString(s"glob:**.{$eh,", ",", "}"))(steps*)

  /**
   * Configuration for building a Spotless formatter step.
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/FormatterStep.java FormatterStep]].
   */
  sealed trait Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/EndWithNewlineStep.java EndWithNewlineStep]]
   */
  case class EndWithNewline() extends Step derives ReadWriter

  /**
   * When `preserve` is set, `steps` are applied outside matching blocks, thus preserving the
   * formatting inside a matching block. Otherwise, `steps` are applied inside matching blocks.
   * @param name name of this step
   * @param regex matcher for a block
   *               - `[regex]`: regular expression
   *               - `[open,close]`: open and close markers
   * @see
   *  - [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/FenceStep.java FenceStep]]
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#spotlessoff-and-spotlesson Toggle]]
   *  - [[https://github.com/diffplug/spotless/tree/main/plugin-gradle#inception-languages-within-languages-within Inception]]
   */
  case class Fence(
      name: String = null,
      regex: Seq[String] = Seq(),
      preserve: Boolean = true,
      steps: Seq[Step]
  ) extends Step derives ReadWriter

  /**
   * @param `type` `TAB` | `SPACE`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/IndentStep.java IndentStep]]
   */
  case class Indent(`type`: String = "TAB", numSpacesPerTab: Option[Int] = None) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/Jsr223Step.java Jsr223Step]]
   */
  case class Jsr223(name: String, dependency: String, engine: String, script: ContentOrFile)
      extends Step
      derives ReadWriter

  /**
   * @param yearMode `PRESERVE` | `UPDATE_TO_TODAY` | `SET_FROM_GIT`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/LicenseHeaderStep.java LicenseHeaderStep]]
   */
  case class LicenseHeader(
      delimiter: String,
      header: ContentOrFile = ContentOrFile(file = "LICENSE"),
      name: String = null,
      contentPattern: String = null,
      yearSeparator: String = "-",
      yearMode: String = "PRESERVE",
      skipLinesMatching: String = null
  ) extends Step derives Reader
  object LicenseHeader {
    def defaultJava = apply(delimiter = "(package|import|public|class|module) ")
    def defaultKotlin = apply(delimiter = "(package |@file|import )")
  }

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/NativeCmdStep.java NativeCmdStep]]
   */
  case class NativeCmd(name: String, pathToExe: String, arguments: Seq[String] = Seq()) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/ReplaceRegexStep.java ReplaceRegexStep]]
   */
  case class ReplaceRegex(name: String, regex: String, replacement: String) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/ReplaceStep.java ReplaceStep]]
   */
  case class Replace(name: String, target: String, replacement: String) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/generic/TrimTrailingWhitespaceStep.java TrimTrailingWhitespaceStep]]
   */
  case class TrimTrailingWhitespace() extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/CleanthatJavaStep.java CleanthatJavaStep]]
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
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/FormatAnnotationsStep.java FormatAnnotationsStep]]
   */
  case class FormatAnnotations(
      addedTypeAnnotations: Seq[String] = Seq(),
      removedTypeAnnotations: Seq[String] = Seq()
  ) extends Step derives ReadWriter

  /**
   * @param style `GOOGLE` | `ASOP`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/GoogleJavaFormatStep.java GoogleJavaFormatStep]]
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
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/ImportOrderStep.java ImportOrderStep]]
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
   * @param style `PALANTIR` | `GOOGLE` | `ASOP`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/PalantirJavaFormatStep.java PalantirJavaFormatStep]]
   */
  case class PalantirJavaFormat(
      version: String = null,
      style: String = "PALANTIR",
      formatJavadoc: Boolean = false
  ) extends Step derives ReadWriter

  /**
   * @param formatter `google-java-format` | `cleanthat-javaparser-unnecessaryimport`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/java/RemoveUnusedImportsStep.java RemoveUnusedImportsStep]]
   */
  case class RemoveUnusedImports(formatter: Option[String] = None) extends Step
      derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/DiktatStep.java DiktatStep]]
   */
  case class Diktat(
      version: String = null,
      isScript: Boolean = false,
      configFile: RelPathRef = RelPathRef("diktat-analysis.yml")
  ) extends Step derives ReadWriter

  /**
   * @param style `DEFAULT` | `META_FORMAT` | `DROPBOX_FORMAT` | `GOOGLE_FORMAT` | `KOTLINLANG_FORMAT`
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtfmtStep.java KtfmtStep]]
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
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/kotlin/KtLintStep.java KtLintStep]]
   */
  case class KtLint(
      version: String = null,
      configFile: RelPathRef = RelPathRef(".editorconfig"),
      customRuleSets: Seq[String] = Seq()
  ) extends Step derives ReadWriter

  /**
   * @see [[https://github.com/diffplug/spotless/blob/main/lib/src/main/java/com/diffplug/spotless/scala/ScalaFmtStep.java ScalaFmtStep]]
   */
  case class ScalaFmt(
      version: String = null,
      scalaMajorVersion: String = null,
      configFile: RelPathRef = RelPathRef(".scalafmt.conf")
  ) extends Step derives ReadWriter

  /**
   * A lint suppression definition.
   * @note When all members are `null`, all lints are suppressed.
   * @param path Relative path to file.
   * @param step Name of formatter step.
   * @param shortCode Name of lint.
   */
  case class Suppress(path: String = null, step: String = null, shortCode: String = null)
      derives ReadWriter

  /**
   * Represents a string value that can be inlined as `content` or must be read from `file`.
   */
  case class ContentOrFile(content: String = null, file: RelPathRef = null) derives ReadWriter

  /**
   * A relative path reference resolved against a [[RelPathRef.withDynamicRoot configurable]] root
   * path. This is meant for usages where the machine specific root path prefix should be omitted.
   */
  case class RelPathRef private (ref: PathRef) {
    @scala.annotation.nowarn("msg=unused")
    private def copy(ref: PathRef = ref) = RelPathRef(ref)
  }
  object RelPathRef {

    private val dynamicRoot = DynamicVariable(BuildCtx.workspaceRoot)

    /**
     * Resolves `rel` path against the current [[dynamicRoot root]] path.
     */
    def apply(rel: os.RelPath): RelPathRef = apply(PathRef(dynamicRoot.value / rel))

    /**
     * Runs `thunk` within a context where [[RelPathRef relative references]] are resolved against
     * the given `root`.
     */
    def withDynamicRoot[T](root: os.Path)(thunk: => T): T =
      dynamicRoot.withValue(root)(thunk)

    given Conversion[String, RelPathRef] = s => apply(os.RelPath(s))

    given ReadWriter[RelPathRef] =
      new Visitor.MapReader[Any, String, RelPathRef](readwriter[String])
        with ReadWriter[RelPathRef] {
        def write0[V](out: Visitor[?, V], v: RelPathRef) =
          summon[ReadWriter[PathRef]].write0(out, v.ref)
        def mapNonNullsFunction(t: String) =
          if t.startsWith("ref:") then RelPathRef(upickle.default.read[PathRef](t))
          else t
      }
  }
}
