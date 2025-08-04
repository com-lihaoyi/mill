package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.{EvaluatorApi, ModuleApi, TaskApi}

trait BspMainModuleApi extends ModuleApi {

  private[mill] def bspClean(
      evaluator: EvaluatorApi,
      tasks: String*
  ): TaskApi[Seq[java.nio.file.Path]]

}
