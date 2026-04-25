package mill.internal

import mill.api.daemon.internal.LauncherOutFiles
import mill.constants.DaemonFiles
import mill.constants.OutFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.OptionConverters.RichOptional

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
    activeCommandMessage: String,
    launcherPid: Long,
    launcherLocks: LauncherSessionState,
    override val runId: String,
    /**
     * Wrapper that runs the given thunk under the cross-process out/ file
     * lock. Setup, publish, and close-time cleanup all mutate `out/` (run-dir
     * creation, dangling-symlink sweeps, atomic-replace publishes), so they
     * must be serialized against concurrent CLI / no-daemon / other-daemon
     * processes that could be doing the same. The caller plumbs in a thunk
     * that takes a brief lease via SharedOutLockManager — refcounting makes
     * this near-free when the lock is already held.
     */
    withFileLockHeld: (=> Unit) => Unit
) extends LauncherOutFiles {
  import LauncherOutFilesImpl.*

  private val runDir = out / LauncherSessionState.runRootDirName / runId
  override val consoleTail: java.nio.file.Path = (runDir / "mill-console-tail").toNIO
  override val profile: java.nio.file.Path = (runDir / OutFiles.millProfile).toNIO
  override val chromeProfile: java.nio.file.Path = (runDir / OutFiles.millChromeProfile).toNIO
  override val dependencyTree: java.nio.file.Path = (runDir / OutFiles.millDependencyTree).toNIO
  override val invalidationTree: java.nio.file.Path = (runDir / OutFiles.millInvalidationTree).toNIO
  // Workspace-level launcher-record file. Visible to other Mill processes
  // (other daemons, no-daemon) so they can identify the holder of the
  // cross-process out/ file lock when waiting for it. Daemon-specific
  // tracking dirs were eliminated so a single source of truth covers both
  // intra-daemon cleanup and cross-process holder identification.
  private val launcherRunFile =
    out / os.RelPath(DaemonFiles.perLauncherFilePath(runId))
  private val closed = new AtomicBoolean(false)

  // Head is the live console-tail symlink, published mid-run by
  // `publishLiveArtifacts`. The remaining entries are the post-run artifacts
  // published by `publishArtifacts` once evaluation finishes.
  private val publishedArtifacts = Seq(
    PublishedArtifact(
      out / DaemonFiles.millConsoleTail,
      os.Path(consoleTail),
      copyFallback = false
    ),
    PublishedArtifact(out / OutFiles.millProfile, os.Path(profile), copyFallback = true),
    PublishedArtifact(
      out / OutFiles.millChromeProfile,
      os.Path(chromeProfile),
      copyFallback = true
    ),
    PublishedArtifact(
      out / OutFiles.millDependencyTree,
      os.Path(dependencyTree),
      copyFallback = true
    ),
    PublishedArtifact(
      out / OutFiles.millInvalidationTree,
      os.Path(invalidationTree),
      copyFallback = true
    )
  )

  withFileLockHeld {
    os.makeDir.all(runDir)
    writeLauncherRunFile()
    cleanup(out, launcherLocks)
  }

  override def publishLiveArtifacts(): Unit =
    if (!closed.get()) withFileLockHeld {
      publishLatest(publishedArtifacts.head, launcherLocks)
    }

  override def publishArtifacts(): Unit =
    if (!closed.get()) withFileLockHeld {
      publishedArtifacts.foreach(publishLatest(_, launcherLocks))
    }

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) withFileLockHeld {
      try mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(launcherRunFile))
      catch { case _: Throwable => }
      cleanup(out, launcherLocks)
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
  private case class PublishedArtifact(link: os.Path, target: os.Path, copyFallback: Boolean)

  private val wellKnownArtifactLinks = Seq(
    os.RelPath(DaemonFiles.millConsoleTail),
    os.RelPath(OutFiles.millProfile),
    os.RelPath(OutFiles.millChromeProfile),
    os.RelPath(OutFiles.millDependencyTree),
    os.RelPath(OutFiles.millInvalidationTree)
  )

  // Run id format is "<millis>-<seq>"; sort key is (millis, seq) so order
  // stays chronological even if millis-string widths ever change.
  private def runDirSortKey(p: os.Path): (Long, Long) = {
    val name = p.last
    val dash = name.indexOf('-')
    if (dash < 0) (0L, 0L)
    else (
      name.substring(0, dash).toLongOption.getOrElse(0L),
      name.substring(dash + 1).toLongOption.getOrElse(0L)
    )
  }

  /**
   * Single pass over `mill-launcher-files/`: returns the set of run-ids whose
   * launcher process is still alive, and as a side effect removes records
   * whose PID is no longer alive (records left behind by abruptly-killed
   * processes from prior runs of any daemon — the workspace-level dir has no
   * single owner, so we GC by liveness here).
   */
  private def sweepAndCollectActiveRunIds(out: os.Path): Set[String] = {
    val dir = out / os.RelPath(DaemonFiles.millLauncherFiles)
    if (!os.exists(dir)) Set.empty
    else {
      val active = Set.newBuilder[String]
      os.list(dir).filter(os.isFile(_)).foreach { file =>
        val pidOpt =
          try ujson.read(os.read(file)).obj.get("pid").map(_.num.toLong)
          catch { case _: Throwable => None }
        val aliveOpt = pidOpt.flatMap(pid =>
          java.lang.ProcessHandle.of(pid).toScala.filter(_.isAlive)
        )
        if (aliveOpt.isDefined) active += file.baseName
        else
          try os.remove(file, checkExists = false)
          catch { case _: Throwable => () }
      }
      active.result()
    }
  }

  /**
   * Cap the total number of retained run dirs at [[maxRetainedRuns]] when we
   * can: never delete a currently-active run dir, and prefer to evict the
   * oldest inactive ones first. If actives alone exceed the cap, all inactive
   * dirs are removed but no actives are touched (so the actual on-disk count
   * may exceed the cap until those launchers exit).
   *
   * Then sweep any `out/mill-*` symlink whose target no longer resolves.
   *
   * Per-link operations are also serialized against in-process publishes via
   * [[withArtifactLock]] so that the "is dangling? then remove" check cannot
   * be invalidated by an interleaving `publishLatest` that has already
   * atomically replaced the link with a fresh valid target. The caller
   * provides the cross-process file-lock guarantee separately (this method
   * is invoked from inside a `withFileLockHeld` block).
   */
  private def cleanup(
      out: os.Path,
      launcherLocks: LauncherSessionState
  ): Unit = {
    try {
      val active = sweepAndCollectActiveRunIds(out)
      val runRootDir = out / LauncherSessionState.runRootDirName
      if (os.exists(runRootDir)) {
        // Sort inactives oldest-first by parsing the leading millis prefix of
        // each run id. This avoids relying on lexicographic ordering staying
        // chronological for fixed-width millis strings.
        val runDirs = os.list(runRootDir).filter(os.isDir(_))
        val eligible = runDirs.filterNot(d => active.contains(d.last)).sortBy(runDirSortKey)
        val toRemoveCount = math.min(
          math.max(0, runDirs.size - maxRetainedRuns),
          eligible.size
        )
        eligible.take(toRemoveCount).foreach(d =>
          try os.remove.all(d)
          catch { case _: Throwable => }
        )
      }

      wellKnownArtifactLinks.foreach { rel =>
        val link = out / rel
        withArtifactLock(link, launcherLocks) {
          if (os.isLink(link) && !os.exists(link, followLinks = true)) {
            try os.remove(link)
            catch { case _: Throwable => }
          }
        }
      }
    } catch { case _: Throwable => }
  }

  /**
   * Per-link, intra-process serialization for the symlink test-then-act
   * sequences in [[cleanup]] and [[publishLatest]]. Cross-process exclusion
   * is provided by the caller's `withFileLockHeld` wrapping.
   */
  private def withArtifactLock[T](
      link: os.Path,
      launcherLocks: LauncherSessionState
  )(body: => T): T = {
    val key = link.toNIO.toAbsolutePath.normalize.toString
    launcherLocks.artifactLockFor(key).synchronized(body)
  }

  /**
   * Atomic-move a symlink at `link` to point at `target`. Uses a sibling tmp
   * file (named with `nanoTime` + a per-session counter so concurrent
   * launchers can't clash) and `ATOMIC_MOVE` so readers never observe an
   * in-progress state.
   */
  private def updateSymlink(
      link: os.Path,
      target: os.Path,
      launcherLocks: LauncherSessionState
  ): Unit = {
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
  }

  private def replaceWithCopy(
      link: os.Path,
      target: os.Path,
      launcherLocks: LauncherSessionState
  ): Unit = {
    os.makeDir.all(link / os.up)
    val tmp =
      link / os.up / s".${link.last}.copy-${System.nanoTime()}-${launcherLocks.nextTmpSuffix()}"
    try {
      try os.remove.all(tmp)
      catch { case _: Throwable => }
      os.copy.over(target, tmp, createFolders = true)
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
  }

  private def publishLatest(
      artifact: PublishedArtifact,
      launcherLocks: LauncherSessionState
  ): Unit = {
    withArtifactLock(artifact.link, launcherLocks) {
      if (os.exists(artifact.target))
        try updateSymlink(artifact.link, artifact.target, launcherLocks)
        catch {
          case e: Throwable =>
            mill.api.Debug(
              s"Failed to publish ${artifact.link.last} as a symlink: ${e.getClass.getSimpleName}: ${e.getMessage}"
            )
            if (artifact.copyFallback)
              replaceWithCopy(artifact.link, artifact.target, launcherLocks)
        }
    }
  }

  /**
   * Prefer a relative symlink so the link survives a move of `out/`. Falls
   * back to the absolute target if link and target don't share a common ancestor.
   */
  private def relativizeTarget(link: os.Path, target: os.Path): os.FilePath =
    try target.relativeTo(link / os.up)
    catch { case _: Throwable => target }
}
