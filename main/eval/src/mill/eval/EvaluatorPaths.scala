package mill.eval

import mill.api.internal
import mill.define.{NamedTask, Segment, Segments}

import java.util.regex.Matcher

case class EvaluatorPaths private (dest: os.Path, meta: os.Path, log: os.Path) {
  private def copy(dest: os.Path = dest, meta: os.Path = meta, log: os.Path = log): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log)
}

object EvaluatorPaths {

  def apply(dest: os.Path, meta: os.Path, log: os.Path): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log)

  private def unapply(evaluatorPaths: EvaluatorPaths): Option[(os.Path, os.Path, os.Path)] =
    Option((evaluatorPaths.dest, evaluatorPaths.meta, evaluatorPaths.log))

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
  private val Colon = "[:]".r

  def sanitizePathSegment(segment: String): os.PathChunk = {
    val seg = segment match {
      case ReservedWinNames(keyword, rest) => s"${keyword}~${rest}"
      case s => s
    }
    val chunk: os.PathChunk = Colon.replaceAllIn(seg, Matcher.quoteReplacement("$colon"))
    chunk
  }
}
