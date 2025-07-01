package mill.api.shared.internal.idea

import mill.api.shared.internal.{PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      segments: mill.api.shared.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
