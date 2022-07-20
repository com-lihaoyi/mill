package mill.eval

import mill.define.{NamedTask, Segments}

trait EvaluatorPathsResolver {
  def resolveDest(task: NamedTask[_]): EvaluatorPaths =
    resolveDest(task.ctx.segments, task.ctx.foreign)
  def resolveDest(segments: Segments, foreignSegments: Option[Segments] = None): EvaluatorPaths
}

object EvaluatorPathsResolver {
  def default(workspacePath: os.Path): EvaluatorPathsResolver =
    new EvaluatorPathsResolver {
      def resolveDest(
          segments: Segments,
          foreignSegments: Option[Segments] = None
      ): EvaluatorPaths =
        EvaluatorPaths.resolveDestPaths(workspacePath, segments, foreignSegments)
    }
}
