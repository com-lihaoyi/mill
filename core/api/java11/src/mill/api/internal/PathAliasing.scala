package mill.api.internal

import mill.api.{BuildCtx, PathRef}
import mill.constants.EnvVars

import java.nio.file.{Files, LinkOption, Path as JPath}
import scala.annotation.tailrec

object PathAliasing {
  val workspaceAliasName = "mill-workspace"
  val homeAliasName = "mill-home"
  val workspaceAlias = "../mill-workspace"
  val homeAlias = "../mill-home"

  /**
   * The standard Mill (abs, alias) mappings (`workspace -> ../mill-workspace`, `home -> ../mill-home`)
   * used by [[ensureProcessCwdAliases]] to install the `../mill-workspace` / `../mill-home`
   * forwarder symlinks.
   */
  def defaultMapping(workspace: os.Path): Seq[(os.Path, os.RelPath)] = Seq(
    workspace -> (os.up / workspaceAliasName),
    os.home -> (os.up / homeAliasName)
  )

  /**
   * The env vars a Mill subprocess needs to participate in path relativization: the workspace
   * root (so it can locate `out/`), and the os-lib relativizer base (so its serialized paths
   * round-trip through the same aliases the daemon uses).
   */
  def workspaceEnvVars(workspace: os.Path = BuildCtx.workspaceRoot): Map[String, String] = {
    val workspaceAbs = PathRef.realAbs(workspace)
    Map(
      EnvVars.MILL_WORKSPACE_ROOT -> workspaceAbs,
      EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> workspacePathRelativizerBase(workspace)
    )
  }

  def workspacePathRelativizerBase(workspace: os.Path = BuildCtx.workspaceRoot): String =
    s"${PathRef.realAbs(workspace)},../mill-workspace;${PathRef.realAbs(os.home)},../mill-home"

  private[mill] final case class SubprocessPathContext(
      taskDest: os.Path,
      aliasParent: os.Path,
      workspaceAliasPath: JPath,
      homeAliasPath: JPath
  ) {
    def pathRelativizerBase(workspace: os.Path): String =
      s"${PathRef.realAbs(workspace)},${workspaceAliasPath};${PathRef.realAbs(os.home)},${homeAliasPath}"

    def serializer(workspace: os.Path): os.Path.Serializer =
      os.Path.pathRemapSerializerNio(
        Seq(
          PathRef.realAbsPath(workspace) -> workspaceAliasPath,
          PathRef.realAbsPath(os.home) -> homeAliasPath
        )
      )

    def prepareAliases(workspace: os.Path): Unit = BuildCtx.withFilesystemCheckerDisabled {
      os.makeDir.all(aliasParent)
      ensureSymlink(aliasParent / workspaceAliasName, workspace)
      ensureSymlink(aliasParent / homeAliasName, os.home)
    }
  }

  private def relativeAliasPath(cwd: os.Path, aliasParent: os.Path, aliasName: String): JPath =
    cwd.wrapped.toAbsolutePath.normalize().relativize(
      (aliasParent / aliasName).wrapped.toAbsolutePath.normalize()
    )

  @tailrec
  private def findDestAncestor(cwd: os.Path): Option[os.Path] = {
    val segments = cwd.segments.toSeq
    if (segments.lastOption.exists(_.endsWith(".dest"))) Some(cwd)
    else if (segments.isEmpty) None
    else {
      val parent = cwd / os.up
      findDestAncestor(parent)
    }
  }

  private[mill] def subprocessPathContext(
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot,
      outPath: Option[os.Path] = None,
      taskDest: Option[os.Path] = None
  ): Option[SubprocessPathContext] = {
    val destOpt = taskDest.filter(cwd.startsWith).orElse {
      findDestAncestor(cwd).filter { dest =>
        outPath.fold(dest.startsWith(workspace))(dest.startsWith)
      }
    }
    destOpt.map { dest =>
      val aliasParent = dest / os.up
      SubprocessPathContext(
        taskDest = dest,
        aliasParent = aliasParent,
        workspaceAliasPath = relativeAliasPath(cwd, aliasParent, workspaceAliasName),
        homeAliasPath = relativeAliasPath(cwd, aliasParent, homeAliasName)
      )
    }
  }

  private[mill] def prepareSubprocessCwd(
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot,
      taskDest: Option[os.Path] = None
  ): Option[SubprocessPathContext] = {
    val context = subprocessPathContext(cwd, workspace, taskDest = taskDest)
    context.foreach(_.prepareAliases(workspace))
    context
  }

  private[mill] def subprocessEnv(
      env: Map[String, String],
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot,
      taskDest: Option[os.Path] = None
  ): Map[String, String] = {
    subprocessPathContext(cwd, workspace, taskDest = taskDest) match {
      case Some(context) =>
        env + (EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> context.pathRelativizerBase(workspace))
      case None =>
        env + (EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> "")
    }
  }

  /** Keep plain user-spawned subprocesses from inheriting the daemon's path relativizer. */
  private[mill] def subprocessBaseEnv(env: Map[String, String]): Map[String, String] =
    env + (EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> "")

  private[mill] def withSubprocessPathSerializer[T](
      cwd: os.Path,
      workspace: os.Path = BuildCtx.workspaceRoot,
      taskDest: Option[os.Path] = None
  )(body: => T): T = {
    subprocessPathContext(cwd, workspace, taskDest = taskDest) match {
      case Some(context) =>
        os.Path.pathSerializer.withValue(context.serializer(workspace))(body)
      case None =>
        BuildCtx.withRawPathSerializer(body)
    }
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
   * Run `body` with a hook installed that prepares aliases next to task `.dest` folders. Subprocess
   * env customization happens at Mill callsites.
   */
  def withSpawnAliasHook[T](workspace: os.Path, outPath: os.Path)(body: => T): T = {
    val prevSpawnHook = os.ProcessOps.spawnHook.value
    os.ProcessOps.spawnHook.withValue { cwd =>
      prevSpawnHook(cwd)
      subprocessPathContext(cwd, workspace, outPath = Some(outPath))
        .foreach(_.prepareAliases(workspace))
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
