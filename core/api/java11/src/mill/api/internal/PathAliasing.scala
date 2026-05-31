package mill.api.internal

import mill.api.BuildCtx
import mill.constants.EnvVars

import java.nio.file.{Files, LinkOption}

object PathAliasing {
  val workspaceAlias = "../mill-workspace"
  val homeAlias = "../mill-home"

  def realAbsPath(p: os.Path): java.nio.file.Path = p.wrapped.toAbsolutePath.normalize()
  def realAbs(p: os.Path): String = realAbsPath(p).toString

  def realAbsResolved(p: os.Path): String =
    try p.wrapped.toRealPath().toString
    catch { case _: java.io.IOException => realAbs(p) }

  /**
   * Resolve `p` to its real on-disk form (following symlinks), falling back to `p` unchanged when
   * it does not exist or cannot be resolved. Use to canonicalize paths that may arrive via the
   * `mill-workspace`/`mill-home` alias symlinks (or platform-level symlinks such as macOS
   * `/tmp` -> `/private/tmp`) before comparing them with `==`/`startsWith`. Resolves against
   * `.wrapped` (the real absolute path), never the relativized alias form.
   */
  def canonicalize(p: os.Path): os.Path =
    try os.Path(p.wrapped.toRealPath())
    catch { case _: java.io.IOException => p }

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
    val workspaceAbs = realAbs(workspace)
    Map(
      EnvVars.MILL_WORKSPACE_ROOT -> workspaceAbs,
      EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> workspacePathRelativizerBase(workspace)
    )
  }

  def workspacePathRelativizerBase(workspace: os.Path = BuildCtx.workspaceRoot): String =
    s"${realAbs(workspace)},../mill-workspace;${realAbs(os.home)},../mill-home"

  private def normalize(raw: String): String = raw.replace('\\', '/')

  def resolveAliasedString(
      rawInput: String,
      workspace: os.Path = BuildCtx.workspaceRoot,
      pwd: os.Path = os.pwd
  ): os.Path = {
    val nio = java.nio.file.Paths.get(rawInput)
    if (nio.isAbsolute) os.Path(nio.toAbsolutePath.normalize())
    else {
      val raw = normalize(rawInput)
      def fromAlias(raw: String, alias: String, base: os.Path): Option[os.Path] = {
        // `base` plus the path suffix following the alias segment, with a leading separator
        // stripped; an empty suffix resolves to `base` itself.
        def resolve(suffix: String): os.Path = {
          val rel = suffix.stripPrefix("/")
          if (rel.isEmpty) base else base / os.RelPath(rel)
        }
        if (raw == alias) Some(base)
        else if (raw.startsWith(alias + "/")) Some(resolve(raw.drop(alias.length)))
        else {
          val needle = s"/$alias"
          val idx = raw.indexOf(needle)
          if (idx >= 0) {
            val suffix = raw.drop(idx + needle.length)
            if (suffix.isEmpty || suffix.startsWith("/")) Some(resolve(suffix))
            else None
          } else None
        }
      }

      fromAlias(raw, workspaceAlias, workspace)
        .orElse(fromAlias(raw, homeAlias, os.home))
        .getOrElse(os.Path(raw, pwd))
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
    // `.wrapped` (real on-disk location), not `.toNIO`: the link *is* the alias, so its serialized
    // form is the `../mill-workspace` alias path itself; handing that relative path to
    // `createSymbolicLink` resolves against the wrong cwd and throws `NoSuchFileException` (a
    // `FileSystemException`, silently swallowed below), so the alias never gets created.
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
   * Scope `body` so the `os.Path` serializer is the default (env-driven) one — typically used to
   * opt OUT of an in-process relativizing serializer that an outer caller installed, so paths
   * within `body` serialize as absolute. Used by long-running processes (launcher, IDEA generator,
   * test runner) whose own output should not be aliased even when their daemon parent's is.
   */
  def withDefaultPathSerializer[T](body: => T): T =
    os.Path.pathSerializer.withValue(os.Path.defaultPathSerializer)(body)

  /**
   * Serializer that always renders a path in its real on-disk absolute form (`p.wrapped`),
   * regardless of any relativization configured via `OS_LIB_PATH_RELATIVIZER_BASE`. Unlike
   * [[withDefaultPathSerializer]], this works even when the relativizing serializer *is* the
   * process default (as in a daemon launched with the relativizer env var set).
   */
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
   * Scope `body` so every `os.Path` serializes to its real absolute on-disk form. Use narrowly,
   * around an `os.proc(...).spawn`/`call`, when handing a `cwd` (and stdout/stderr redirects) to
   * `ProcessBuilder`: os-lib turns `cwd` into `ProcessBuilder.directory()` via `cwd.toIO`, which the
   * OS resolves on the real filesystem, so a relativized `../mill-workspace/...` form would not
   * resolve. (`realAbs` can't be used here because the `cwd` is consumed by os-lib, not the caller.)
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
   * Install the `../mill-workspace` / `../mill-home` forwarder symlinks for a process whose cwd is
   * `cwd`. The aliases live in `cwd`'s *parent*, never inside `cwd` itself, so that a tool which
   * walks/archives its own working directory (`jar -c .`, `tar`, `os.walk`) does not see them.
   */
  def ensureProcessCwdAliases(
      cwd: os.Path,
      workspace: => os.Path = BuildCtx.workspaceRoot
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
