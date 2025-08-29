package mill.javalib

import mill.api.Task

/**
 * @param compileTo the directory to which the classes will be compiled. We need this because we can't use the task's
 *                  destination folder as we need that folder to be persistent and persistent tasks can not take
 *                  arguments.
 * @param forceSemanticDb if true then generates semanticdb files even if [[semanticDbWillBeNeeded]] returns false.
 *                        This is useful when you have tasks like scalafix which need to generate semanticdb files
 *                        even if the Mill BSP client doesn't need them.
 */
private[mill] case class CompileArgs(
    compileTo: os.Path,
    forceSemanticDb: Boolean
)
private[mill] object CompileArgs {
  // TODO review: this is a terrible hack
  def compileTaskPath(thisTaskDest: os.Path, compileTask: Task.Named[?]): os.Path =
    thisTaskDest / ".." / s"${compileTask.ctx.segments.last.value}.dest"

  /** Arguments for the default compilation. */
  def default(compileTo: os.Path): CompileArgs = CompileArgs(compileTo, forceSemanticDb = false)
}
