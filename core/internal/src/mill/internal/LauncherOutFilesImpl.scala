package mill.internal

import mill.api.daemon.internal.LauncherOutFiles
import mill.constants.DaemonFiles
import mill.constants.OutFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

private[mill] class LauncherOutFilesImpl(
    out: os.Path,
    activeCommandMessage: String,
    launcherPid: Long,
    outFilesState: LauncherOutFilesState,
    private val runId: String
) extends LauncherOutFiles {
  import LauncherOutFilesImpl.*

  private val runDir = out / LauncherOutFilesState.runRootDirName / runId
  override val consoleTail: java.nio.file.Path = (runDir / "mill-console-tail").toNIO
  override val profile: java.nio.file.Path = (runDir / OutFiles.millProfile).toNIO
  override val chromeProfile: java.nio.file.Path = (runDir / OutFiles.millChromeProfile).toNIO
  override val dependencyTree: java.nio.file.Path = (runDir / OutFiles.millDependencyTree).toNIO
  override val invalidationTree: java.nio.file.Path = (runDir / OutFiles.millInvalidationTree).toNIO
  private val closed = new AtomicBoolean(false)

  private val publishedOutFiles = Seq(
    PublishedOutFile(
      out / DaemonFiles.millConsoleTail,
      os.Path(consoleTail),
      copyFallback = false
    ),
    PublishedOutFile(out / OutFiles.millProfile, os.Path(profile), copyFallback = true),
    PublishedOutFile(
      out / OutFiles.millChromeProfile,
      os.Path(chromeProfile),
      copyFallback = true
    ),
    PublishedOutFile(
      out / OutFiles.millDependencyTree,
      os.Path(dependencyTree),
      copyFallback = true
    ),
    PublishedOutFile(
      out / OutFiles.millInvalidationTree,
      os.Path(invalidationTree),
      copyFallback = true
    )
  )

  // Write the launcher record before creating the run directory. Both live
  // under `mill-run/`: `<runId>.json` records active launchers, while
  // `<runId>/` stores run artifacts. A concurrent cleanup treats any run
  // directory whose runId is missing from the active record set as eligible
  // for deletion, so the record must be visible before the directory exists.
  writeLauncherRunFile()
  os.makeDir.all(runDir)
  cleanup(out, outFilesState)

  override def publishLiveArtifacts(): Unit =
    if (!closed.get()) publishLatest(publishedOutFiles.head, outFilesState)

  override def publishArtifacts(): Unit =
    if (!closed.get()) publishedOutFiles.foreach(publishLatest(_, outFilesState))

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      LauncherOutFilesRecordStore.remove(out, runId)
      cleanup(out, outFilesState)
    }

  private def writeLauncherRunFile(): Unit =
    LauncherOutFilesRecordStore.write(out, runId, launcherPid, activeCommandMessage)
}

private[mill] object LauncherOutFilesImpl {
  private val maxRetainedRuns = 10
  private case class PublishedOutFile(link: os.Path, target: os.Path, copyFallback: Boolean)

  private val wellKnownOutFileLinks = Seq(
    os.RelPath(DaemonFiles.millConsoleTail),
    os.RelPath(OutFiles.millProfile),
    os.RelPath(OutFiles.millChromeProfile),
    os.RelPath(OutFiles.millDependencyTree),
    os.RelPath(OutFiles.millInvalidationTree)
  )

  // Names are `<millis>-<pid>-<counter>`; sort primarily by millis, then by the
  // trailing counter so that within a single millisecond we still keep newest.
  // Older single-segment formats fall back to (0, 0).
  private def runDirSortKey(p: os.Path): (Long, Long) = {
    val parts = p.last.split('-')
    if (parts.isEmpty) (0L, 0L)
    else (
      parts.head.toLongOption.getOrElse(0L),
      parts.last.toLongOption.getOrElse(0L)
    )
  }

  private def cleanup(
      out: os.Path,
      outFilesState: LauncherOutFilesState
  ): Unit = {
    try {
      val active = LauncherOutFilesRecordStore.sweepActive(out).iterator.map(_.runId).toSet
      val runRootDir = out / LauncherOutFilesState.runRootDirName
      if (os.exists(runRootDir)) {
        val runDirs = os.list(runRootDir).filter(os.isDir(_))
        val eligible = runDirs.filterNot(d => active.contains(d.last)).sortBy(runDirSortKey)
        val toRemoveCount = math.max(0, eligible.size - maxRetainedRuns)
        eligible.take(toRemoveCount).foreach(d =>
          try os.remove.all(d)
          catch { case _: Throwable => () }
        )
      }

      wellKnownOutFileLinks.foreach { rel =>
        val link = out / rel
        withPublishedPathLock(link, outFilesState) {
          if (os.isLink(link) && !os.exists(link, followLinks = true)) {
            try os.remove(link)
            catch { case _: Throwable => () }
          }
        }
      }
    } catch { case _: Throwable => () }
  }

  private def withPublishedPathLock[T](
      link: os.Path,
      outFilesState: LauncherOutFilesState
  )(body: => T): T = {
    val key = link.toNIO.toAbsolutePath.normalize.toString
    outFilesState.publishedPathLockFor(key).synchronized(body)
  }

  private def replaceAtomically(
      link: os.Path,
      tempName: String
  )(writeTemp: os.Path => Unit): Unit = {
    os.makeDir.all(link / os.up)
    val tmp = link / os.up / tempName
    try {
      try os.remove.all(tmp)
      catch { case _: Throwable => () }
      writeTemp(tmp)
      java.nio.file.Files.move(
        tmp.toNIO,
        link.toNIO,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    } finally {
      try os.remove.all(tmp)
      catch { case _: Throwable => () }
    }
  }

  private def updateSymlink(
      link: os.Path,
      target: os.Path,
      outFilesState: LauncherOutFilesState
  ): Unit = {
    val rel = relativizeTarget(link, target)
    replaceAtomically(
      link,
      s".${link.last}.tmp-${System.nanoTime()}-${outFilesState.nextTmpSuffix()}"
    )(tmp => os.symlink(tmp, rel))
  }

  private def replaceWithCopy(
      link: os.Path,
      target: os.Path,
      outFilesState: LauncherOutFilesState
  ): Unit = {
    replaceAtomically(
      link,
      s".${link.last}.copy-${System.nanoTime()}-${outFilesState.nextTmpSuffix()}"
    )(tmp => os.copy.over(target, tmp, createFolders = true))
  }

  private def publishLatest(
      outFile: PublishedOutFile,
      outFilesState: LauncherOutFilesState
  ): Unit = {
    withPublishedPathLock(outFile.link, outFilesState) {
      if (os.exists(outFile.target))
        try updateSymlink(outFile.link, outFile.target, outFilesState)
        catch {
          case e: Throwable =>
            mill.api.Debug(
              s"Failed to publish ${outFile.link.last} as a symlink: ${e.getClass.getSimpleName}: ${e.getMessage}"
            )
            if (outFile.copyFallback)
              replaceWithCopy(outFile.link, outFile.target, outFilesState)
        }
    }
  }

  private def relativizeTarget(link: os.Path, target: os.Path): os.FilePath =
    try target.relativeTo(link / os.up)
    catch { case _: Throwable => target }
}
