package mill.api.internal

trait MainModuleApi extends ModuleApi {
  private[mill] def bspClean(
      evaluator: EvaluatorApi,
      targets: String*
  ): TaskApi[Seq[java.nio.file.Path]]
}
