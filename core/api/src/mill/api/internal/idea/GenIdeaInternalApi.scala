package mill.api.internal.idea

import mill.api.internal.{PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      segments: mill.api.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
