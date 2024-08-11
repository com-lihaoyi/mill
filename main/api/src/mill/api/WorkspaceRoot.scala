package mill.api

object WorkspaceRoot {
  val workspaceRoot = sys.env.get("MILL_WORKSPACE_ROOT").fold(os.pwd)(os.Path(_, os.pwd))
}
