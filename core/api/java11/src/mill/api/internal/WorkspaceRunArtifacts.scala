package mill.api.internal

import mill.constants.DaemonFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*

private object WorkspaceRunArtifacts {
  private val nextTiebreaker = new AtomicLong(0L)
  val runRootDirName = "mill-run"
  private val legacyRunDirPrefix = "mill-run-"
  private val maxRetainedRuns = 10
  private val inactiveRunCleanupGracePeriodMillis = 5.seconds.toMillis

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${nextTiebreaker.getAndIncrement()}"

  final case class RunInfo(pid: Long, command: String)

  final class RunArtifacts(
      runId: String,
      out: os.Path,
      daemonDir: os.Path,
      runInfo: RunInfo
  ) extends AutoCloseable {
    private val runDir = out / runRootDirName / runId
    private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.launcherRun(runId))
    private val activeRun = ActiveRun(runId, runDir, runDir / "mill-console-tail")
    private val coordinator = coordinatorFor(out)
    private val closed = new AtomicBoolean(false)

    val consoleTail: os.Path = activeRun.consoleTail

    os.makeDir.all(runDir)
    coordinator.register(activeRun)
    writeLauncherRunFile()

    def artifactPath(default: os.Path): os.Path = {
      val target =
        if (default.startsWith(out)) runDir / default.relativeTo(out)
        else runDir / default.last
      os.makeDir.all(target / os.up)
      coordinator.recordPublishedFile(activeRun, default, target)
      target
    }

    def publish(): Unit =
      if (!closed.get()) coordinator.publish(activeRun)

    def cleanupOldRunDirs(): Unit =
      coordinator.cleanupOldRunDirs()

    override def close(): Unit =
      if (closed.compareAndSet(false, true)) {
        coordinator.deactivate(activeRun)
        try mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(launcherRunFile))
        catch { case _: Throwable => }
        coordinator.cleanupOldRunDirs()
      }

    private def writeLauncherRunFile(): Unit = {
      val commandJson = ujson.write(ujson.Str(runInfo.command))
      val json = s"""{"pid":${runInfo.pid},"command":$commandJson}"""
      try {
        mill.api.BuildCtx.withFilesystemCheckerDisabled {
          os.makeDir.all(launcherRunFile / os.up)
          os.write.over(launcherRunFile, json)
        }
      } catch { case _: Throwable => }
    }
  }

  private case class ActiveRun(
      runId: String,
      runDir: os.Path,
      consoleTail: os.Path,
      var active: Boolean = true,
      var inactiveSinceMillis: Long = 0L,
      var published: Boolean = false,
      publishedFiles: scala.collection.mutable.Map[os.Path, os.Path] =
        scala.collection.mutable.LinkedHashMap.empty
  )

  private final class RunArtifactsCoordinator(out: os.Path) {
    private val lock = new Object
    private val runs = scala.collection.mutable.LinkedHashMap.empty[String, ActiveRun]

    def register(run: ActiveRun): Unit = lock.synchronized { runs.update(run.runId, run) }

    def publish(run: ActiveRun): Unit = lock.synchronized {
      if (!run.published) {
        run.published = true
        refreshWellKnownLinks()
      }
    }

    def recordPublishedFile(run: ActiveRun, link: os.Path, target: os.Path): Unit =
      lock.synchronized {
        run.publishedFiles.update(link, target)
        refreshWellKnownLinks()
      }

    def deactivate(run: ActiveRun): Unit = lock.synchronized {
      run.active = false
      run.inactiveSinceMillis = System.currentTimeMillis()
      refreshWellKnownLinks()
    }

    def cleanupOldRunDirs(): Unit = {
      try {
        if (os.exists(out)) {
          lock.synchronized {
            val now = System.currentTimeMillis()
            val protectedRunDirs = runs.valuesIterator
              .filter(run =>
                run.active ||
                  run.inactiveSinceMillis == 0L ||
                  now - run.inactiveSinceMillis < inactiveRunCleanupGracePeriodMillis
              )
              .map(_.runDir)
              .toSet

            val runRootDir = out / runRootDirName
            val runDirs =
              if (os.exists(runRootDir)) os.list(runRootDir).filter(os.isDir(_)).sortBy(_.last)
              else Seq.empty

            val legacyRunDirs = os.list(out)
              .filter(p => os.isDir(p) && p.last.startsWith(legacyRunDirPrefix))
              .sortBy(_.last)

            val removable = runDirs.filterNot(protectedRunDirs)
            val toRemove = removable.take(runDirs.size - maxRetainedRuns)
            (toRemove ++ legacyRunDirs).foreach(dir =>
              try os.remove.all(dir)
              catch { case _: Throwable => }
            )

            val before = runs.size
            runs.retain((_, run) => os.exists(run.runDir))
            if (runs.size != before) refreshWellKnownLinks()
          }
        }
      } catch {
        case _: Throwable =>
      }
    }

    private def refreshWellKnownLinks(): Unit = {
      val publishedRuns = runs.valuesIterator.filter(_.published).toSeq

      updateSymlink(out / DaemonFiles.millConsoleTail, publishedRuns.lastOption.map(_.consoleTail))
      val publishedLinks = runs.valuesIterator.flatMap(_.publishedFiles.keys).toSet
      publishedLinks.foreach { link =>
        updateSymlink(
          link,
          publishedRuns.reverseIterator.flatMap(_.publishedFiles.get(link)).nextOption()
        )
      }
    }
  }

  private val coordinators = new ConcurrentHashMap[String, RunArtifactsCoordinator]()

  private def coordinatorFor(out: os.Path): RunArtifactsCoordinator =
    coordinators.computeIfAbsent(out.toString, _ => new RunArtifactsCoordinator(out))

  private def updateSymlink(link: os.Path, targetOpt: Option[os.Path]): Unit = {
    try {
      targetOpt match {
        case Some(target) =>
          os.makeDir.all(link / os.up)
          val rel = target.relativeTo(link / os.up)
          val tmp =
            link / os.up / s".${link.last}.tmp-${System.nanoTime()}-${nextTiebreaker.getAndIncrement()}"
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
        case None =>
          try os.remove.all(link)
          catch { case _: Throwable => }
      }
    } catch {
      case _: Throwable =>
    }
  }
}
