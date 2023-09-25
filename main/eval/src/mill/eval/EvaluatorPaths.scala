package mill.eval

import mill.api.internal
import mill.define.{NamedTask, Segment, Segments, Target}

case class EvaluatorPaths private (dest: os.Path, meta: os.Path, log: os.Path, tmp: os.Path) {
  private def copy(dest: os.Path = dest, meta: os.Path = meta, log: os.Path = log, tmp: os.Path = tmp): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log, tmp)
}

object EvaluatorPaths {

  def apply(dest: os.Path, meta: os.Path, log: os.Path): EvaluatorPaths =
    new EvaluatorPaths(dest, meta, log, dest / os.up / s"${dest.baseName}.tmp")

  private def unapply(evaluatorPaths: EvaluatorPaths): Option[(os.Path, os.Path, os.Path)] =
    Option(evaluatorPaths.dest, evaluatorPaths.meta, evaluatorPaths.log)

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
    val targetPath = workspacePath / segmentStrings
    EvaluatorPaths(
      targetPath / os.up / s"${targetPath.last}.dest",
      targetPath / os.up / s"${targetPath.last}.json",
      targetPath / os.up / s"${targetPath.last}.log",
      targetPath / os.up / s"${targetPath.last}.tmp"
    )
  }
  def resolveDestPaths(
      workspacePath: os.Path,
      task: NamedTask[_]
  ): EvaluatorPaths = resolveDestPaths(workspacePath, task.ctx.segments, task.ctx.foreign)
}
