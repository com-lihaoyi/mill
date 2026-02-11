package mill.api.internal

import mill.api.BuildCtx

object PathAliasing {
  val workspaceAlias = "out/mill-workspace"
  val homeAlias = "out/mill-home"

  private def normalize(raw: String): String = raw.replace('\\', '/')

  private def resolveFromAlias(base: os.Path, raw: String, aliasIdx: Int, alias: String): os.Path = {
    val suffix = raw.substring(aliasIdx + alias.length).stripPrefix("/")
    if (suffix.isEmpty) base else base / os.RelPath(suffix)
  }

  def resolveAliasedString(
      rawInput: String,
      workspace: os.Path = BuildCtx.workspaceRoot,
      pwd: os.Path = os.pwd
  ): os.Path = {
    val nio = java.nio.file.Paths.get(rawInput)
    if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
    else {
      val raw = normalize(rawInput)
      if (raw == workspaceAlias) workspace
      else if (raw.startsWith(workspaceAlias + "/"))
        workspace / os.RelPath(raw.stripPrefix(workspaceAlias + "/"))
      else if (raw == homeAlias) os.home
      else if (raw.startsWith(homeAlias + "/"))
        os.home / os.RelPath(raw.stripPrefix(homeAlias + "/"))
      else {
        val workspaceIdx = raw.indexOf(workspaceAlias)
        if (workspaceIdx >= 0) resolveFromAlias(workspace, raw, workspaceIdx, workspaceAlias)
        else {
          val homeIdx = raw.indexOf(homeAlias)
          if (homeIdx >= 0) resolveFromAlias(os.home, raw, homeIdx, homeAlias)
          else os.Path(raw, pwd)
        }
      }
    }
  }

  def resolveAliasedPath(
      path: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot,
      pwd: os.Path = os.pwd
  ): os.Path = {
    val nio = path.wrapped
    if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
    else resolveAliasedString(path.toString, workspace, pwd)
  }
}
