package mill.api.internal.idea

import mill.api.internal.{EvaluatorApi, PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      evaluator: EvaluatorApi,
      segments: mill.api.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
