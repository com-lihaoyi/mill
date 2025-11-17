package mill.api.daemon.internal

import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.api.daemon.internal.eclipse.GenEclipseInternalApi
import mill.api.daemon.internal.idea.{GenIdeaInternalApi, GenIdeaModuleApi}
import mill.api.daemon.internal.{EvaluatorApi, ModuleApi, TaskApi, UnresolvedPathApi}

trait MillBuildRootModuleApi {

  def bspScriptIgnoreAll: TaskApi[Seq[String]]
}

