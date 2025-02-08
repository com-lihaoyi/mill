package mill.eval

import mill.define.{NamedTask, Segments}

trait EvaluatorPathsResolver {
  def resolveDest(task: NamedTask[?]): EvaluatorPaths = resolveDest(task.ctx.segments)
  def resolveDest(segments: Segments): EvaluatorPaths
}

object EvaluatorPathsResolver {
  def default(workspacePath: os.Path): EvaluatorPathsResolver =
    new EvaluatorPathsResolver {
      def resolveDest(segments: Segments): EvaluatorPaths =
        EvaluatorPaths.resolveDestPaths(workspacePath, segments)
    }
}
