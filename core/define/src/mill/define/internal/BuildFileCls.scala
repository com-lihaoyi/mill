package mill.define.internal

class BuildFileCls(rootModule0: => mill.define.RootModule0) extends mill.api.internal.BuildFileApi {
  def value = this
  def checker = mill.define.internal.ResolveChecker(mill.define.BuildCtx.workspaceRoot)
  def rootModule = os.checker.withValue(checker) { rootModule0 }

  def moduleWatchedValues = mill.define.BuildCtx.watchedValues.toSeq
  def evalWatchedValues = mill.define.BuildCtx.evalWatchedValues
}
