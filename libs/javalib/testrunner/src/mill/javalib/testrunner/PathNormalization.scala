package mill.javalib.testrunner

import mill.constants.EnvVars

private[testrunner] object PathNormalization {
  private def relativizerMappings: Seq[(os.Path, String)] = {
    sys.env.get(EnvVars.OS_LIB_PATH_RELATIVIZER_BASE).toSeq
      .flatMap(_.split(";").iterator)
      .flatMap { entry =>
        val idx = entry.indexOf(',')
        if (idx <= 0 || idx >= entry.length - 1) None
        else Some(os.Path(entry.substring(0, idx), os.pwd) -> entry.substring(idx + 1))
      }
      .toSeq
  }

  private def workspaceRootAbs: os.Path = {
    sys.env.get(EnvVars.MILL_WORKSPACE_ROOT)
      .map(p => os.Path(p, os.pwd))
      .orElse(relativizerMappings.collectFirst {
        case (from, "out/mill-workspace") => from
      })
      .getOrElse(mill.api.BuildCtx.workspaceRoot)
  }

  def normalizePath(path: os.Path): os.Path = {
    val workspaceAbs = workspaceRootAbs
    val homeAbs = os.Path(os.home.wrapped.toAbsolutePath.normalize())
    val raw = path.wrapped.toString.replace('\\', '/')
    val workspaceAlias = "out/mill-workspace"
    val homeAlias = "out/mill-home"

    def resolveFromAlias(base: os.Path, aliasIdx: Int, alias: String): os.Path = {
      val suffix = raw.substring(aliasIdx + alias.length).stripPrefix("/")
      if (suffix.isEmpty) base else base / os.RelPath(suffix)
    }

    if (raw == workspaceAlias) workspaceAbs
    else if (raw.startsWith(workspaceAlias + "/"))
      workspaceAbs / os.RelPath(raw.stripPrefix(workspaceAlias + "/"))
    else if (raw == homeAlias) homeAbs
    else if (raw.startsWith(homeAlias + "/"))
      homeAbs / os.RelPath(raw.stripPrefix(homeAlias + "/"))
    else {
      val workspaceIdx = raw.indexOf(workspaceAlias)
      if (workspaceIdx >= 0) resolveFromAlias(workspaceAbs, workspaceIdx, workspaceAlias)
      else {
        val homeIdx = raw.indexOf(homeAlias)
        if (homeIdx >= 0) resolveFromAlias(homeAbs, homeIdx, homeAlias)
        else if (path.wrapped.isAbsolute) os.Path(path.wrapped.toAbsolutePath.normalize())
        else os.Path(raw, workspaceAbs)
      }
    }
  }
}
