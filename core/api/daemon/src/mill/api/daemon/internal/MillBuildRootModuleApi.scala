package mill.api.daemon.internal

import mill.api.daemon.internal.TaskApi

trait MillBuildRootModuleApi {

  def bspScriptIgnoreAll: TaskApi[Seq[String]]
}
