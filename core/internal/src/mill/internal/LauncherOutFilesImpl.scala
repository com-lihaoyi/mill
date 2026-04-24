package mill.internal

import mill.api.daemon.internal.LauncherOutFiles
import mill.constants.DaemonFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One run's concrete [[LauncherOutFiles]] handle: per-run scratch directory at
 * `out/mill-run/<runId>/`, routing of well-known artifact paths (profile,
 * chrome-profile, dependency-tree, etc.) into that directory, two-phase publish
 * of top-level `out/mill-*` symlinks, and lifecycle of the
 * `mill-launcher-files/<runId>.json` record that advertises this launcher as
 * live to other launchers and to cleanup sweeps.
 *
 * Active-vs-finished is encoded entirely on disk: the launcher file exists
 * iff the run is active. This run only rewrites top-level symlinks for paths
 * it actually produced; concurrent runs that each produce different files
 * therefore coexist naturally. Dangling symlinks (e.g. left behind after a run
 * dir is pruned) are swept at cleanup time.
 */
private[mill] final class LauncherOutFilesImpl(
    out: os.Path,
    daemonDir: os.Path,
    activeCommandMessage: String,
    launcherPid: Long,
    launcherLocks: LauncherLockingState,
    override val runId: String
) extends LauncherOutFiles {
  import LauncherOutFilesImpl.*

  private val runDir = out / LauncherLockingState.runRootDirName / runId
  override val consoleTail: java.nio.file.Path = (runDir / "mill-console-tail").toNIO
  private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.perLauncherFilePath(runId))
  private val ownedArtifactPaths = ConcurrentHashMap.newKeySet[os.RelPath]()
  private val closed = new AtomicBoolean(false)

  os.makeDir.all(runDir)
  writeLauncherRunFile()
  cleanup(out, daemonDir)

  override def artifactPath(default: java.nio.file.Path): java.nio.file.Path = {
    val defaultPath = os.Path(default)
    val rel =
      if (defaultPath.startsWith(out)) defaultPath.relativeTo(out)
      else os.RelPath(defaultPath.last)
    ownedArtifactPaths.add(rel)
    val target = runDir / rel
    os.makeDir.all(target / os.up)
    target.toNIO
  }

  override def publishLiveArtifacts(): Unit =
    if (!closed.get())
      updateSymlink(out / DaemonFiles.millConsoleTail, os.Path(consoleTail), launcherLocks)

  override def publishArtifacts(): Unit =
    if (!closed.get()) {
      val consoleTailPath = os.Path(consoleTail)
      if (os.exists(consoleTailPath))
        updateSymlink(out / DaemonFiles.millConsoleTail, consoleTailPath, launcherLocks)
      ownedArtifactPaths.forEach { rel =>
        val target = runDir / rel
        if (os.exists(target)) updateSymlink(out / rel, target, launcherLocks)
      }
    }

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      try mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(launcherRunFile))
      catch { case _: Throwable => }
      cleanup(out, daemonDir)
    }

  private def writeLauncherRunFile(): Unit = {
    val commandJson = ujson.write(ujson.Str(activeCommandMessage))
    val json = s"""{"pid":$launcherPid,"command":$commandJson}"""
    try mill.api.BuildCtx.withFilesystemCheckerDisabled {
        os.makeDir.all(launcherRunFile / os.up)
        os.write.over(launcherRunFile, json)
      }
    catch { case _: Throwable => }
  }
}

private[mill] object LauncherOutFilesImpl {
  private val maxRetainedRuns = 10

  /**
   * Set of run-ids whose launcher process is still alive. "Still alive" means
   * the `mill-launcher-files/<runId>.json` record exists AND the PID recorded
   * in it is a live process. The PID check catches launchers that were killed
   * before they could remove their own record.
   */
  private def activeRunIds(daemonDir: os.Path): Set[String] = {
    val dir = daemonDir / os.RelPath(DaemonFiles.millLauncherFiles)
    if (!os.exists(dir)) Set.empty
    else os.list(dir).iterator
      .filter(os.isFile(_))
      .flatMap { file =>
        val runId = file.baseName
        val pidOpt =
          try ujson.read(os.read(file)).obj.get("pid").map(_.num.toLong)
          catch { case _: Throwable => None }
        pidOpt.filter(pid => java.lang.ProcessHandle.of(pid).isPresent).map(_ => runId)
      }
      .toSet
  }

  /**
   * Keep the N most recent run dirs plus any currently-active ones; remove the
   * rest. Then sweep any `out/mill-*` symlink whose target no longer resolves.
   */
  private def cleanup(out: os.Path, daemonDir: os.Path): Unit = {
    try {
      val runRootDir = out / LauncherLockingState.runRootDirName
      if (os.exists(runRootDir)) {
        val active = activeRunIds(daemonDir)
        val runDirs = os.list(runRootDir).filter(os.isDir(_)).sortBy(_.last)
        val eligible = runDirs.filterNot(d => active.contains(d.last))
        val toRemove = eligible.take(runDirs.size - maxRetainedRuns)
        toRemove.foreach(d =>
          try os.remove.all(d)
          catch { case _: Throwable => }
        )
      }

      if (os.exists(out)) {
        os.list(out).filter(os.isLink(_)).foreach { link =>
          if (!os.exists(link, followLinks = true)) {
            try os.remove(link)
            catch { case _: Throwable => }
          }
        }
      }
    } catch { case _: Throwable => }
  }

  /**
   * Atomic-move a symlink at `link` to point at `target`. Uses a sibling tmp
   * file and `ATOMIC_MOVE` so readers never observe an in-progress state.
   */
  private def updateSymlink(
      link: os.Path,
      target: os.Path,
      launcherLocks: LauncherLockingState
  ): Unit = {
    try {
      os.makeDir.all(link / os.up)
      val rel = relativizeTarget(link, target)
      val tmp =
        link / os.up / s".${link.last}.tmp-${System.nanoTime()}-${launcherLocks.nextTmpSuffix()}"
      try {
        try os.remove.all(tmp)
        catch { case _: Throwable => }
        os.symlink(tmp, rel)
        java.nio.file.Files.move(
          tmp.toNIO,
          link.toNIO,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } finally {
        try os.remove.all(tmp)
        catch { case _: Throwable => }
      }
    } catch { case _: Throwable => }
  }

  /**
   * Prefer a relative symlink so the link survives a move of `out/`. Falls
   * back to the absolute target if link and target don't share a common ancestor.
   */
  private def relativizeTarget(link: os.Path, target: os.Path): os.FilePath =
    try target.relativeTo(link / os.up)
    catch { case _: Throwable => target }
}
