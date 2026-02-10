package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object GetTestTasks {

  private def normalizePath(path: os.Path): os.Path = {
    val workspaceRoot = sys.env
      .get("MILL_WORKSPACE_ROOT")
      .map(p => os.Path(p, os.pwd))
      .getOrElse(os.pwd)
    val workspaceAbs = os.Path(workspaceRoot.wrapped.toAbsolutePath.normalize())
    val homeAbs = os.Path(os.home.wrapped.toAbsolutePath.normalize())
    val nio = path.wrapped
    if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
    else {
      val raw = nio.toString.replace('\\', '/')
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
          else os.Path(raw, workspaceAbs)
        }
      }
    }
  }

  def apply(args0: mill.javalib.api.internal.ZincOp.GetTestTasks): Seq[String] = {
    import args0.*
    val globFilter = TestRunnerUtils.globFilter(selectors)
    val normalizedRunCp = runCp.map(normalizePath)
    val normalizedTestCp = testCp.map(normalizePath)
    mill.util.Jvm.withClassLoader(
      classPath = normalizedRunCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .getTestTasks0(
          Framework.framework(framework),
          Seq.from(normalizedTestCp),
          args,
          cls => globFilter(cls.getName),
          classLoader
        )
        .toSeq
    }
  }
}
