package mill.api.internal

import mill.api.{BuildCtx, PathRef}
import mill.constants.EnvVars

import java.nio.file.{Files, LinkOption}

object PathAliasing {
  val workspaceAlias = "../mill-workspace"
  val homeAlias = "../mill-home"

  /**
   * The standard Mill (abs, alias) mappings (`workspace -> ../mill-workspace`, `home -> ../mill-home`)
   * used by [[ensureProcessCwdAliases]] to install the `../mill-workspace` / `../mill-home`
   * forwarder symlinks.
   */
  def defaultMapping(workspace: os.Path): Seq[(os.Path, os.RelPath)] = Seq(
    workspace -> (os.up / "mill-workspace"),
    os.home -> (os.up / "mill-home")
  )

  /**
   * The env vars a Mill subprocess needs to participate in path relativization: the workspace
   * root (so it can locate `out/`), and the os-lib relativizer base (so its serialized paths
   * round-trip through the same aliases the daemon uses).
   */
  def workspaceEnvVars(workspace: os.Path = BuildCtx.workspaceRoot): Map[String, String] = {
    // Env vars cross process boundaries, so use a real path instead of a cwd-relative alias.
    val workspaceAbs = PathRef.toAbsString(workspace)
    Map(
      EnvVars.MILL_WORKSPACE_ROOT -> workspaceAbs,
      EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> workspacePathRelativizerBase(workspace)
    )
  }

  def workspacePathRelativizerBase(workspace: os.Path = BuildCtx.workspaceRoot): String = {
    // The relativizer base itself must name the real workspace before giving its alias.
    val workspaceAbs = PathRef.toAbsString(workspace)
    // The relativizer base itself must name the real home directory before giving its alias.
    val homeAbs = PathRef.toAbsString(os.home)
    s"$workspaceAbs,../mill-workspace;$homeAbs,../mill-home"
  }

  def workspaceRootPathRelativizerBase(workspace: os.Path = BuildCtx.workspaceRoot): String = {
    // The relativizer base itself must name the real workspace before giving its alias.
    val workspaceAbs = PathRef.toAbsString(workspace)
    // The relativizer base itself must name the real home directory before giving its alias.
    val homeAbs = PathRef.toAbsString(os.home)
    s"$workspaceAbs,out/mill-workspace;$homeAbs,out/mill-home"
  }

  private def isWorkspaceRootCwd(cwd: os.Path, workspace: os.Path): Boolean =
    cwd != null && PathRef.toAbsNioPath(cwd) == PathRef.toAbsNioPath(workspace)

  private def ups(count: Int): os.RelPath =
    Iterator.fill(count)(os.up).foldLeft(os.rel)(_ / _)

  private def taskDestAliasPrefix(cwd: os.Path, workspace: os.Path): Option[os.RelPath] = {
    val out = workspace / "out"
    if (!cwd.startsWith(out)) None
    else {
      val segments = cwd.relativeTo(out).segments
      segments.indexWhere(_.endsWith(".dest")) match {
        case -1 => None
        case destIndex => Some(ups(segments.length - destIndex))
      }
    }
  }

  private def aliasPrefixForCwd(cwd: os.Path, workspace: os.Path): Option[os.RelPath] =
    if (isWorkspaceRootCwd(cwd, workspace)) Some(os.rel / "out")
    else taskDestAliasPrefix(cwd, workspace)

  def aliasMappingForCwd(
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot
  ): Seq[(os.Path, os.RelPath)] =
    aliasPrefixForCwd(cwd, workspace).toSeq.flatMap { prefix =>
      Seq(
        workspace -> (prefix / "mill-workspace"),
        os.home -> (prefix / "mill-home")
      )
    }

  def pathRelativizerBaseForCwd(
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot
  ): String =
    aliasMappingForCwd(cwd, workspace)
      .map { case (target, alias) =>
        // The relativizer base itself must name the real target before giving its alias.
        s"${PathRef.toAbsString(target)},$alias"
      }
      .mkString(";")

  def workspaceEnvVarsForCwd(
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot
  ): Map[String, String] =
    workspaceEnvVars(workspace).updated(
      EnvVars.OS_LIB_PATH_RELATIVIZER_BASE,
      pathRelativizerBaseForCwd(cwd, workspace)
    )

  /**
   * Create or update `link` to be a symlink pointing at `dest`. If `link` exists as a non-symlink,
   * replace it. Best-effort: catches `FileSystemException` (e.g. read-only fs) and
   * `FileAlreadyExistsException` (concurrent creation).
   */
  def ensureSymlink(link: os.Path, dest: os.Path): Unit = {
    // `.wrapped` (real on-disk location), not `.toNIO`: the link *is* the alias, so its serialized
    // form may itself be relative. Handing that relative path to `createSymbolicLink` resolves
    // against the wrong cwd and throws `NoSuchFileException` (a `FileSystemException`, silently
    // swallowed below), so the alias never gets created.
    val targetNio = link.wrapped
    val destNio = dest.wrapped.toAbsolutePath.normalize()
    val linkOpts = LinkOption.NOFOLLOW_LINKS
    try {
      if (Files.isSymbolicLink(targetNio)) {
        val current = Files.readSymbolicLink(targetNio)
        if (current != destNio) {
          os.remove(link)
          Files.createSymbolicLink(targetNio, destNio)
        }
      } else if (Files.exists(targetNio, linkOpts)) {
        os.remove.all(link)
        Files.createSymbolicLink(targetNio, destNio)
      } else {
        Files.createSymbolicLink(targetNio, destNio)
      }
    } catch {
      case _: java.nio.file.FileAlreadyExistsException =>
      // Another concurrent task/process created it between exists-check and symlink — accept it.
      case _: java.nio.file.FileSystemException =>
      // Best-effort alias setup: read-only fs, no-symlink-support, etc.
    }
  }

  /**
   * Scope `body` so the `os.Path` serializer is the default (env-driven) one. Used by long-running
   * in-process tools whose own output should not inherit an outer caller's custom serializer.
   */
  def withDefaultPathSerializer[T](body: => T): T =
    os.Path.pathSerializer.withValue(os.Path.defaultPathSerializer)(body)

  private object RawPathSerializer extends os.Path.Serializer {
    def serializeString(p: os.Path): String = p.wrapped.toString
    def serializeFile(p: os.Path): java.io.File = p.wrapped.toFile
    def serializePath(p: os.Path): java.nio.file.Path = p.wrapped
    def deserialize(s: String): java.nio.file.Path = os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.io.File): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.nio.file.Path): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.net.URI): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
  }

  /**
   * Scope `body` so os-lib's `ProcessBuilder` inputs (`cwd`, stdout/stderr redirects) use real
   * on-disk paths. Keep this narrow: ordinary user-facing path strings should go through PathRef.
   */
  def withRawPathSerializer[T](body: => T): T =
    os.Path.pathSerializer.withValue(RawPathSerializer)(body)

  /**
   * Run `body` with an `os.ProcessOps.spawnHook` installed that ensures every subprocess's
   * `cwd` has the `../mill-workspace`/`../mill-home` aliases. Chains onto any existing hook
   * so installations from outer scopes (e.g. `EvaluatorImpl`) still run.
   */
  def withSpawnAliasHook[T](workspace: os.Path)(body: => T): T = {
    val prev = os.ProcessOps.spawnHook.value
    os.ProcessOps.spawnHook.withValue { cwd =>
      prev(cwd)
      ensureProcessCwdAliases(cwd, workspace)
    }(body)
  }

  /**
   * Install the `mill-workspace` / `mill-home` forwarder symlinks for a process whose cwd is
   * `cwd`. The aliases live outside the cwd: normally in its parent, but for cwd values under a
   * task `.dest` directory, in the containing `.dest` directory's parent. That keeps tools which
   * walk/archive their own working directory (`jar -c .`, `tar`, `os.walk`) from seeing aliases
   * while still letting nested cwd values resolve paths like `../../../mill-workspace/...`.
   *
   * The exception is a subprocess spawned directly at the workspace root. In that case, the
   * path relativizer needs `out/mill-workspace` / `out/mill-home`, so put the forwarders there
   * instead of outside the workspace.
   */
  def ensureProcessCwdAliases(
      cwd: os.Path,
      workspace: => os.Path = BuildCtx.workspaceRoot
  ): Unit = {
    if (cwd == null) return
    val workspace0 = workspace
    aliasPrefixForCwd(cwd, workspace0) match {
      case None =>
      case Some(prefix) =>
        val parent = cwd / prefix
        val parentNio = parent.toNIO
        val linkOpts = LinkOption.NOFOLLOW_LINKS
        // The enclosing dir is not actually a directory; cannot host the aliases (best-effort).
        if (Files.exists(parentNio, linkOpts) && !Files.isDirectory(parentNio, linkOpts)) return
        BuildCtx.withFilesystemCheckerDisabled {
          os.makeDir.all(parent)
          for ((target, alias) <- defaultMapping(workspace0))
            ensureSymlink(parent / alias.last, target)
        }
    }
  }
}
