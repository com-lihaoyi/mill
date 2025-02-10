package mill.exec

import mill.define.{NamedTask, Segments}

trait ExecutionPathsResolver {
  def resolveDest(task: NamedTask[?]): ExecutionPaths = resolveDest(task.ctx.segments)
  def resolveDest(segments: Segments): ExecutionPaths
}

object ExecutionPathsResolver {
  def default(workspacePath: os.Path): ExecutionPathsResolver =
    new ExecutionPathsResolver {
      def resolveDest(segments: Segments): ExecutionPaths =
        ExecutionPaths.resolveDestPaths(workspacePath, segments)
    }
}
