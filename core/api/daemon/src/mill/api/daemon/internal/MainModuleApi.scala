package mill.api.daemon.internal

trait MainModuleApi extends ModuleApi {
  private[mill] def bspClean(
      evaluator: EvaluatorApi,
      tasks: String*
  ): TaskApi[Seq[java.nio.file.Path]]
}
