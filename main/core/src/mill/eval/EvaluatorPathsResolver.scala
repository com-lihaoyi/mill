package mill.eval

import mill.define.NamedTask

trait EvaluatorPathsResolver {
  def resolveDest(task: NamedTask[_]): EvaluatorPaths
}

object EvaluatorPathsResolver {
  def default(workspacePath: os.Path): EvaluatorPathsResolver =
    new EvaluatorPathsResolver {
      override def resolveDest(task: NamedTask[_]): EvaluatorPaths =
        EvaluatorPaths.resolveDestPaths(workspacePath, task.ctx.segments, task.ctx.foreign)
    }
}
