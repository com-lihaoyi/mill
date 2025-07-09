package mill.api.internal

import mill.api.BuildCtx

class BuildFileCls(rootModule0: => mill.api.internal.RootModule)
    extends mill.api.daemon.internal.BuildFileApi {
  def value = this
  def checker = mill.api.internal.ResolveChecker(BuildCtx.workspaceRoot)
  val rootModule = os.checker.withValue(checker) { rootModule0 }

  def moduleWatchedValues = BuildCtx.watchedValues.toSeq
  def evalWatchedValues = BuildCtx.evalWatchedValues
}
