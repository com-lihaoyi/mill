package mill.api

import mill.client.EnvVars
object WorkspaceRoot {
  val workspaceRoot: os.Path =
    sys.env.get(EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_, os.pwd))
}
