package mill.define.internal

import mill.define.BuildCtx

class BuildFileCls(rootModule0: => mill.define.internal.RootModule0) extends mill.api.internal.BuildFileApi {
  def value = this
  def checker = mill.define.internal.ResolveChecker(BuildCtx.workspaceRoot)
  val rootModule = os.checker.withValue(checker) { rootModule0 }

  def moduleWatchedValues = BuildCtx.watchedValues.toSeq
  def evalWatchedValues = BuildCtx.evalWatchedValues
}
