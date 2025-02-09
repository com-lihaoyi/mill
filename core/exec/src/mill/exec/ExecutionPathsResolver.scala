package mill.exec

import mill.define.{NamedTask, Segments}

trait EvaluatorPathsResolver {
  def resolveDest(task: NamedTask[?]): ExecutionPaths = resolveDest(task.ctx.segments)
  def resolveDest(segments: Segments): ExecutionPaths
}

object EvaluatorPathsResolver {
  def default(workspacePath: os.Path): EvaluatorPathsResolver =
    new EvaluatorPathsResolver {
      def resolveDest(segments: Segments): ExecutionPaths =
        ExecutionPaths.resolveDestPaths(workspacePath, segments)
    }
}
