package mill.api.internal

import mill.api.BuildCtx
import mill.constants.EnvVars

import java.nio.file.{Files, LinkOption}

object PathAliasing {
  val workspaceAlias = "../mill-workspace"
  val homeAlias = "../mill-home"

  /**
   * The standard Mill (abs, alias) mappings: `workspace -> ../mill-workspace`, `home -> ../mill-home`.
   * Suitable for `os.Path.pathRemapSerializerNio` (after `.toNIO`-ing each side) and for
   * [[ensureProcessCwdAliases]]'s symlink installation.
   */
  def defaultMapping(workspace: os.Path): Seq[(os.Path, os.RelPath)] = Seq(
    workspace -> (os.up / "mill-workspace"),
    os.home -> (os.up / "mill-home")
  )

  private def normalize(raw: String): String = raw.replace('\\', '/')

  private def resolveFromAlias(
      base: os.Path,
      raw: String,
      aliasIdx: Int,
      alias: String
  ): os.Path = {
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

  /**
   * Create or update `link` to be a symlink pointing at `dest`. If `link` exists as a non-symlink,
   * replace it. Best-effort: catches `FileSystemException` (e.g. read-only fs) and
   * `FileAlreadyExistsException` (concurrent creation).
   */
  def ensureSymlink(link: os.Path, dest: os.Path): Unit = {
    val targetNio = link.toNIO
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
   * Scope `body` so the `os.Path` serializer is the default (env-driven) one — typically used to
   * opt OUT of an in-process relativizing serializer that an outer caller installed, so paths
   * within `body` serialize as absolute. Used by long-running processes (launcher, IDEA generator,
   * test runner) whose own output should not be aliased even when their daemon parent's is.
   */
  def withDefaultPathSerializer[T](body: => T): T =
    os.Path.pathSerializer.withValue(os.Path.defaultPathSerializer)(body)

  /**
   * Install the `../mill-workspace` / `../mill-home` forwarder symlinks for a process whose cwd is
   * `cwd`. The aliases live in `cwd`'s *parent*, never inside `cwd` itself, so that a tool which
   * walks/archives its own working directory (`jar -c .`, `tar`, `os.walk`) does not see them.
   */
  def ensureProcessCwdAliases(
      cwd: os.Path,
      workspace: => os.Path = sys.env
        .get(EnvVars.MILL_WORKSPACE_ROOT)
        .map(p => os.Path(p, os.pwd))
        .getOrElse(BuildCtx.workspaceRoot)
  ): Unit = {
    if (cwd == null) return
    val parent = cwd / os.up
    val parentNio = parent.toNIO
    val linkOpts = LinkOption.NOFOLLOW_LINKS
    // The enclosing dir is not actually a directory; cannot host the aliases (best-effort).
    if (Files.exists(parentNio, linkOpts) && !Files.isDirectory(parentNio, linkOpts)) return
    BuildCtx.withFilesystemCheckerDisabled {
      os.makeDir.all(parent)
      for ((target, alias) <- defaultMapping(workspace)) ensureSymlink(parent / alias.last, target)
    }
  }
}
