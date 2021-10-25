package mill.eval

import mill.define.{NamedTask, Segment, Segments}

case class EvaluatorPaths(out: os.Path, dest: os.Path, meta: os.Path, log: os.Path)

object EvaluatorPaths {
  private[eval] def makeSegmentStrings(segments: Segments) = segments.value.flatMap {
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
    EvaluatorPaths(targetPath, targetPath / "dest", targetPath / "meta.json", targetPath / "log")
  }
  def resolveDestPaths(
      workspacePath: os.Path,
      task: NamedTask[_]
  ): EvaluatorPaths = resolveDestPaths(workspacePath, task.ctx.segments, task.ctx.foreign)
}
