package mill.internal

import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.api.daemon.internal.LauncherLocking.HolderInfo
import mill.constants.DaemonFiles

import java.io.PrintStream
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One run's concrete handle into workspace locking and artifact routing.
 * Implements both [[LauncherLocking]] and [[LauncherOutFiles]] over the
 * same underlying state: callers take whichever aspect they need and the
 * implementation keeps them coordinated internally (e.g. closing the session
 * releases any outstanding leases and deactivates the artifact routing).
 *
 * Cross-run sharing (the locks shared among concurrent launchers in the same
 * daemon, plus the run-id counter) is passed in as [[LauncherLocks]] rather
 * than pulled from process-wide globals: the daemon holds one
 * [[LauncherLocks]] for its lifetime and threads it through each session.
 *
 * Active-vs-finished is encoded by the presence of
 * `mill-launcher-files/<runId>.json` in the daemon dir (written on construct,
 * deleted on `close()`). This run only ever rewrites top-level `out/mill-*`
 * symlinks for paths it actually produced; concurrent runs that each produce
 * different files therefore coexist naturally. Dangling symlinks (e.g. left
 * behind after a run dir is pruned) are swept at cleanup time.
 */
private[mill] final class LauncherLockingImpl(
    out: os.Path,
    daemonDir: os.Path,
    activeCommandMessage: String,
    launcherPid: Long,
    waitingErr: PrintStream,
    noBuildLock: Boolean,
    noWaitForBuildLock: Boolean,
    shared: LauncherLocks
) extends LauncherLocking with LauncherOutFiles {
  import LauncherLockingImpl.*

  private val holder = HolderInfo(launcherPid, activeCommandMessage)
  override val runId: String = shared.nextRunId()
  private val runDir = out / LauncherLocks.runRootDirName / runId
  override val consoleTail: java.nio.file.Path = (runDir / "mill-console-tail").toNIO
  private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.perLauncherFilePath(runId))

  private val closed = new AtomicBoolean(false)
  private val activeLeases = scala.collection.mutable.Set.empty[LeaseWrapper]
  private val ownedArtifactPaths = ConcurrentHashMap.newKeySet[os.RelPath]()

  os.makeDir.all(runDir)
  writeLauncherRunFile()
  cleanup(out, daemonDir)

  private def ensureOpen(): Unit =
    if (closed.get()) throw new IllegalStateException(s"Lock session $runId is closed")

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

  override def publishLiveArtifacts(): Unit = {
    ensureOpen()
    updateSymlink(out / DaemonFiles.millConsoleTail, os.Path(consoleTail))
  }

  override def publishArtifacts(): Unit = {
    ensureOpen()
    val consoleTailPath = os.Path(consoleTail)
    if (os.exists(consoleTailPath))
      updateSymlink(out / DaemonFiles.millConsoleTail, consoleTailPath)
    ownedArtifactPaths.forEach { rel =>
      val target = runDir / rel
      if (os.exists(target)) updateSymlink(out / rel, target)
    }
  }

  override def metaBuildLock(
      depth: Int,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.metaBuildLock(depth, kind)
    else acquireManagedLease(shared.metaBuildLockFor(depth).acquire(
      kind,
      waitingErr,
      noWaitForBuildLock,
      holder
    ))
  }

  override def taskLock(
      path: java.nio.file.Path,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.taskLock(path, kind)
    else {
      val normalized = path.toAbsolutePath.normalize().toString
      val lock = shared.taskLockFor(normalized)
      acquireManagedLease(lock.acquire(kind, waitingErr, noWaitForBuildLock, holder))
    }
  }

  private def acquireManagedLease(
      underlying: LauncherLocking.Lease
  ): LauncherLocking.Lease = {
    val lease = new LeaseWrapper(underlying)
    val accepted = activeLeases.synchronized {
      if (closed.get()) false
      else {
        activeLeases += lease
        true
      }
    }
    if (!accepted) {
      try underlying.close()
      catch { case _: Throwable => }
      throw new IllegalStateException(s"Lock session $runId is closed")
    }
    lease
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      val leases = activeLeases.synchronized(activeLeases.toSeq)
      leases.foreach(lease =>
        try lease.close()
        catch { case _: Throwable => }
      )
      try mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(launcherRunFile))
      catch { case _: Throwable => }
      cleanup(out, daemonDir)
    }
  }

  private def writeLauncherRunFile(): Unit = {
    val commandJson = ujson.write(ujson.Str(activeCommandMessage))
    val json = s"""{"pid":$launcherPid,"command":$commandJson}"""
    try mill.api.BuildCtx.withFilesystemCheckerDisabled {
      os.makeDir.all(launcherRunFile / os.up)
      os.write.over(launcherRunFile, json)
    } catch { case _: Throwable => }
  }

  /**
   * Atomic-move a symlink at `link` to point at `target`. Uses a sibling tmp
   * file and `ATOMIC_MOVE` so readers never observe an in-progress state.
   */
  private def updateSymlink(link: os.Path, target: os.Path): Unit = {
    try {
      os.makeDir.all(link / os.up)
      val rel = relativizeTarget(link, target)
      val tmp =
        link / os.up / s".${link.last}.tmp-${System.nanoTime()}-${shared.nextTmpSuffix()}"
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

  private final class LeaseWrapper(
      underlying: LauncherLocking.Lease
  ) extends LauncherLocking.Lease {
    private val closed = new AtomicBoolean(false)

    override def downgradeToRead(): Unit =
      if (!closed.get()) underlying.downgradeToRead()

    override def close(): Unit =
      if (closed.compareAndSet(false, true)) {
        underlying.close()
        activeLeases.synchronized(activeLeases -= this)
      }
  }
}

private[mill] object LauncherLockingImpl {
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
      val runRootDir = out / LauncherLocks.runRootDirName
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
   * Prefer a relative symlink so the link survives a move of `out/`. Falls
   * back to the absolute target if link and target don't share a common ancestor.
   */
  private def relativizeTarget(link: os.Path, target: os.Path): os.FilePath =
    try target.relativeTo(link / os.up)
    catch { case _: Throwable => target }
}

