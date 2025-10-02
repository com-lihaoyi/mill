package mill.api

object WorkspaceRoot {
  @deprecated("Use mill.api.BuildCtx.workspaceRoot instead", "Mill 0.12.17")
  val workspaceRoot: os.Path = BuildCtx.workspaceRoot
}
