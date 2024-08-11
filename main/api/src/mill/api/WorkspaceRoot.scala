package mill.api

import os.Path
object WorkspaceRoot {
  val workspaceRoot: Path = sys.env.get("MILL_WORKSPACE_ROOT").fold(os.pwd)(os.Path(_, os.pwd))
}
