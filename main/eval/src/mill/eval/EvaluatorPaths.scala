package mill.eval

import mill.api.internal
import mill.define.{NamedTask, Segment, Segments}

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
    val targetPath = workspacePath / segmentStrings
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
}
