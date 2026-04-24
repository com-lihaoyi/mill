package mill.api.internal

import mill.api.internal.WorkspaceLocking.*
import mill.constants.DaemonFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

private object WorkpaceLockingUtils {
  private val nextTiebreaker = new AtomicLong(0L)

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${nextTiebreaker.getAndIncrement()}"

  case class LockOwner(runId: String, pid: Long, command: String)

  enum LockId {
    case MetaBuild
    case SelectiveExecution(path: String)
    case Task(path: String)
  }
  object LockId {
    def selectiveExecution(path: os.Path): LockId.SelectiveExecution =
      LockId.SelectiveExecution(path.toNIO.toAbsolutePath.normalize().toString)

    def task(path: os.Path): LockId.Task =
      LockId.Task(path.toNIO.toAbsolutePath.normalize().toString)
  }

  trait ManagedLease extends DowngradableLease

  final class LockRegistry {
    private val states = new ConcurrentHashMap[LockId, FairRwLock]()

    def acquire(
        id: LockId,
        kind: LockKind,
        owner: LockOwner,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): ManagedLease = {
      val lock = states.computeIfAbsent(id, _ => new FairRwLock(id))
      lock.acquire(kind, owner, waitingErr, noWait)
    }
  }

  /**
   * A small fair read/write lock with lease-based ownership rather than thread-based ownership.
   *
   * We cannot use `java.util.concurrent.locks.ReentrantReadWriteLock` here because Mill acquires
   * task/meta-build locks on worker threads, may downgrade them to read locks, and then retains
   * those read leases until later cleanup on a different thread. The standard library RW locks
   * tie lock ownership to the acquiring thread, while this lock ties ownership to the returned
   * lease object instead.
   */
  private final class FairRwLock(id: LockId) {
    private val monitor = new Object
    private var readerCount = 0
    private var writerActive = false
    private var waitingWriters = 0
    private var writerOwner = Option.empty[LockOwner]
    private val readerOwners = scala.collection.mutable.LinkedHashMap.empty[String, (LockOwner, Int)]

    private def canAcquire(kind: LockKind): Boolean =
      kind match {
        case LockKind.Read => !writerActive && waitingWriters == 0
        case LockKind.Write => !writerActive && readerCount == 0
      }

    private def describeBlocker(kind: LockKind): String = {
      val blocker = kind match {
        case LockKind.Read => writerOwner
        case LockKind.Write => writerOwner.orElse(readerOwners.headOption.map(_._2._1))
      }
      val command = blocker.map(_.command).getOrElse("<unknown>")
      val pid = blocker.map(_.pid.toString).getOrElse("<unknown>")
      s"Another Mill command in the current daemon is running '$command' with PID $pid"
    }

    private def addReader(owner: LockOwner): Unit =
      readerOwners.get(owner.runId) match {
        case Some((existing, count)) => readerOwners.update(owner.runId, (existing, count + 1))
        case None => readerOwners.update(owner.runId, (owner, 1))
      }

    private def removeReader(owner: LockOwner): Unit =
      readerOwners.get(owner.runId).foreach {
        case (_, count) if count <= 1 => readerOwners.remove(owner.runId)
        case (existing, count) => readerOwners.update(owner.runId, (existing, count - 1))
      }

    def acquire(
        initialKind: LockKind,
        owner: LockOwner,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): ManagedLease = {
      val shouldWait = monitor.synchronized {
        if (canAcquire(initialKind)) false
        else if (noWait) {
          throw new Exception(s"${describeBlocker(initialKind)} and using resource '$id', failing")
        } else {
          if (initialKind == LockKind.Write) waitingWriters += 1
          true
        }
      }

      if (shouldWait) {
        waitingErr.println(
          s"${monitor.synchronized(describeBlocker(initialKind))}, waiting for it to be done... " +
            s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
        )
      }

      monitor.synchronized {
        val waitingWriterRegistered = shouldWait && initialKind == LockKind.Write
        try {
          while (!canAcquire(initialKind)) monitor.wait()
        } catch {
          case e: Throwable =>
            if (waitingWriterRegistered) {
              waitingWriters -= 1
              monitor.notifyAll()
            }
            throw e
        }
        if (waitingWriterRegistered) {
          waitingWriters -= 1
        }

        initialKind match {
          case LockKind.Read =>
            readerCount += 1
            addReader(owner)
          case LockKind.Write =>
            writerActive = true
            writerOwner = Some(owner)
        }

        new ManagedLease {
          private var currentKind = initialKind
          private var closed = false

          override def kind: LockKind = monitor.synchronized(currentKind)

          override def downgradeToRead(): Unit = monitor.synchronized {
            if (!closed && currentKind == LockKind.Write) {
              writerActive = false
              writerOwner = None
              readerCount += 1
              addReader(owner)
              currentKind = LockKind.Read
              monitor.notifyAll()
            }
          }

          override def close(): Unit = monitor.synchronized {
            if (!closed) {
              currentKind match {
                case LockKind.Read =>
                  readerCount -= 1
                  removeReader(owner)
                case LockKind.Write =>
                  writerActive = false
                  writerOwner = None
              }
              closed = true
              monitor.notifyAll()
            }
          }
        }
      }
    }
  }

  private val registries = new ConcurrentHashMap[String, LockRegistry]()

  def locksFor(out: os.Path): LockRegistry =
    registries.computeIfAbsent(out.toString, _ => new LockRegistry)

  val runRootDirName = "mill-run"
  private val legacyRunDirPrefix = "mill-run-"
  private val maxRetainedRuns = 10
  private val inactiveRunCleanupGracePeriodMillis = 5.seconds.toMillis

  final class RunArtifacts(
      runId: String,
      out: os.Path,
      daemonDir: os.Path,
      owner: LockOwner
  ) extends AutoCloseable {
    private val runDir = out / runRootDirName / runId
    private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.launcherRun(runId))
    private val activeRun = ActiveRun(runId, runDir, runDir / "mill-console-tail")
    private val coordinator = coordinatorFor(out)
    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

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
      val commandJson = ujson.write(ujson.Str(owner.command))
      val json = s"""{"pid":${owner.pid},"command":$commandJson}"""
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
