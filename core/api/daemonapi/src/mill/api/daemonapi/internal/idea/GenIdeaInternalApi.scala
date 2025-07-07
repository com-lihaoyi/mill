package mill.api.daemonapi.internal.idea

import mill.api.daemonapi.internal.{PathRefApi, TaskApi}

trait GenIdeaInternalApi {

  private[mill] def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      segments: mill.api.daemonapi.Segments
  ): TaskApi[ResolvedModule]

  private[mill] def ideaCompileOutput: TaskApi[PathRefApi]

}
