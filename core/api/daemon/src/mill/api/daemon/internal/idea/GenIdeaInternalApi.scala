package mill.api.daemon.internal.idea

import mill.api.daemon.internal.{PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      segments: mill.api.daemon.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
