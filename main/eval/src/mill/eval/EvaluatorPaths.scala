package mill.eval

import mill.api.internal
import mill.define.{NamedTask, Segment, Segments}

import java.util.regex.Matcher

case class EvaluatorPaths private (dest: os.Path, meta: os.Path, log: os.Path) {
  // scalafix:off; we want to hide the generic copy method
  private def copy(dest: os.Path = dest, meta: os.Path = meta, log: os.Path = log): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log)
  // scalafix:on
}

object EvaluatorPaths {

  def apply(dest: os.Path, meta: os.Path, log: os.Path): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log)

  // scalafix:off; we want to hide the generic unapply method
  private def unapply(evaluatorPaths: EvaluatorPaths): Option[(os.Path, os.Path, os.Path)] =
    Option((evaluatorPaths.dest, evaluatorPaths.meta, evaluatorPaths.log))
  // scalafix:on

  @internal
  private[mill] def makeSegmentStrings(segments: Segments): Seq[String] = segments.value.flatMap {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(values) => values.map(_.toString)
  }
  def resolveDestPaths(
      workspacePath: os.Path,
      segments: Segments,
      foreignSegments: Option[Segments] = None
  ): EvaluatorPaths = {
    val refinedSegments = foreignSegments.map(_ ++ segments).getOrElse(segments)
    val segmentStrings = makeSegmentStrings(refinedSegments)
    val targetPath = workspacePath / segmentStrings.map(sanitizePathSegment)
    EvaluatorPaths(
      targetPath / os.up / s"${targetPath.last}.dest",
      targetPath / os.up / s"${targetPath.last}.json",
      targetPath / os.up / s"${targetPath.last}.log"
    )
  }
  def resolveDestPaths(
      workspacePath: os.Path,
      task: NamedTask[_]
  ): EvaluatorPaths = resolveDestPaths(workspacePath, task.ctx.segments, task.ctx.foreign)

  // case-insensitive match on reserved names
  private val ReservedWinNames =
    raw"^([cC][oO][nN]|[pP][rR][nN]|[aA][uU][xX]|[nN][uU][lL]|[cC][oO][mM][0-9¹²³]|[lL][pP][tT][0-9¹²³])($$|[.].*$$)".r
  // Colons are not supported on Windows
  private val Colon = "[:]".r
  // Dollar sign `$` is our masking-character
  private val Dollar = "[$]".r
  // Forward-slashed are reserved for directory delimiters
  private val Slash = "/".r

  private val steps: Seq[String => String] = Seq(
    // Step 1: mask all existing dollar signs, so we can use the dollar as masking character
    s => Dollar.replaceAllIn(s, Matcher.quoteReplacement("$$")),
    // Step 2: mask reserved Windows names, like CON1 or LPT1
    _ match {
      case ReservedWinNames(keyword, rest) => s"${keyword}~${rest}"
      case s => s
    },
    // Step 3: Replace colon (:) with $colon
    s => Colon.replaceAllIn(s, Matcher.quoteReplacement("$colon")),
    // Step 4: Replace slash (/) with $slash
    s => Slash.replaceAllIn(s, Matcher.quoteReplacement("$slash"))
  )

  def sanitizePathSegment(segment: String): os.PathChunk = {
    // sanitize and implicitly convert to PathChunk
    steps.foldLeft(segment) { (segment, f) => f(segment) }
  }
}
