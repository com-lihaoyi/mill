package mill.api.internal.idea

import mill.api.internal.{EvaluatorApi, PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaMetadata(
      ideaConfigVersion: Int,
      evaluator: EvaluatorApi,
      path: mill.api.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
