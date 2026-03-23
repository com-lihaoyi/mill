package mill.api.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  case class Resource(key: String, kind: LockKind)

  trait Lease extends AutoCloseable

  trait Manager extends AutoCloseable {
    def runId: String
    def consoleTail: os.Path
    def noBuildLock: Boolean
    def noWaitForBuildLock: Boolean

    /** Returns a per-run profile path to avoid corruption under concurrent daemon runs. */
    def profilePath(default: os.Path): os.Path = default
    /** Returns a per-run chrome profile path to avoid corruption under concurrent daemon runs. */
    def chromeProfilePath(default: os.Path): os.Path = default

    def acquireLocks(resources: Seq[Resource]): Lease
    def withLocks[T](resources: Seq[Resource])(t: => T): T = {
      val lease = acquireLocks(resources)
      try t
      finally lease.close()
    }
    def acquireMetaBuildRead(depth: Int): Lease =
      acquireLocks(Seq(Resource(s"meta-build:$depth", LockKind.Read)))
    def withMetaBuildRead[T](depth: Int)(t: => T): T =
      withLocks(Seq(Resource(s"meta-build:$depth", LockKind.Read)))(t)
    def withMetaBuildWrite[T](depth: Int)(t: => T): T =
      withLocks(Seq(Resource(s"meta-build:$depth", LockKind.Write)))(t)
  }

  object NoopManager extends Manager {
    override def runId: String = "noop"
    override def consoleTail: os.Path = os.pwd / "out" / "mill-console-tail"
    override def noBuildLock: Boolean = false
    override def noWaitForBuildLock: Boolean = false
    override def acquireLocks(resources: Seq[Resource]): Lease = () => ()
    override def close(): Unit = ()
  }

  // Monotonic tiebreaker so that two InProcessManagers created in the same
  // millisecond still get distinct runIds.
  private val nextTiebreaker = new AtomicLong(0L)

  // Never evicted; bounded by the number of distinct lock keys (tasks + meta-build depths)
  private val lockTable = new ConcurrentHashMap[String, ReentrantReadWriteLock]()

  private val runDirPrefix = "mill-run-"

  /** Maximum number of per-run directories to retain for debugging. */
  private val maxRetainedRuns = 10

  /**
   * Clean up old per-run directories in `out`, keeping the most recent [[maxRetainedRuns]].
   * Directories are named `mill-run-{timestamp}-{counter}` and sort chronologically.
   */
  private def cleanupOldRunDirs(out: os.Path): Unit = {
    try {
      if (!os.exists(out)) return
      val runDirs = os.list(out)
        .filter(p => os.isDir(p) && p.last.startsWith(runDirPrefix))

      if (runDirs.size > maxRetainedRuns) {
        val toRemove = runDirs.sortBy(_.last).dropRight(maxRetainedRuns)
        toRemove.foreach { dir =>
          try os.remove.all(dir)
          catch { case _: Throwable => }
        }
      }
    } catch {
      case _: Throwable => // best-effort cleanup
    }
  }

  /** Atomically replace `link` with a relative symlink pointing to `target`. */
  private def updateSymlink(link: os.Path, target: os.Path): Unit = {
    try {
      val rel = target.relativeTo(link / os.up)
      os.remove(link)
      os.symlink(link, rel)
    } catch {
      case _: Throwable => // best-effort; non-critical for correctness
    }
  }

  final class InProcessManager(
      out: os.Path,
      override val noBuildLock: Boolean,
      override val noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String =
      s"${System.currentTimeMillis()}-${nextTiebreaker.getAndIncrement()}"

    private val runDir: os.Path = out / s"$runDirPrefix$runId"
    os.makeDir.all(runDir)

    override val consoleTail: os.Path = runDir / "mill-console-tail"

    // Clean up old run directories, then point well-known symlinks at this run
    cleanupOldRunDirs(out)
    updateSymlink(out / "mill-console-tail", consoleTail)

    override def profilePath(default: os.Path): os.Path = {
      val path = runDir / default.last
      updateSymlink(default, path)
      path
    }

    override def chromeProfilePath(default: os.Path): os.Path = {
      val path = runDir / default.last
      updateSymlink(default, path)
      path
    }

    override def close(): Unit = ()

    override def acquireLocks(resources: Seq[Resource]): Lease =
      if (noBuildLock || resources.isEmpty) () => ()
      else {
        val sorted = resources.distinct.sortBy(r => (r.key, r.kind.toString))
        val acquired = scala.collection.mutable.Buffer.empty[Resource]
        sorted.foreach { resource =>
          acquire(resource)
          acquired += resource
        }
        () => acquired.reverseIterator.foreach(release)
      }

    private def rwLock(resource: Resource): ReentrantReadWriteLock =
      lockTable.computeIfAbsent(resource.key, _ => new ReentrantReadWriteLock(true))

    private def acquire(resource: Resource): Unit = {
      val lock = rwLock(resource)
      val acquired = resource.kind match {
        case LockKind.Read =>
          if (noWaitForBuildLock) lock.readLock().tryLock() else {
            lock.readLock().lock()
            true
          }
        case LockKind.Write =>
          if (noWaitForBuildLock) lock.writeLock().tryLock() else {
            lock.writeLock().lock()
            true
          }
      }

      if (!acquired) {
        throw new Exception(
          s"Another Mill command in the current daemon is using resource '${resource.key}', failing"
        )
      }
    }

    private def release(resource: Resource): Unit = {
      val lock = rwLock(resource)
      resource.kind match {
        case LockKind.Read => lock.readLock().unlock()
        case LockKind.Write => lock.writeLock().unlock()
      }
    }
  }
}
