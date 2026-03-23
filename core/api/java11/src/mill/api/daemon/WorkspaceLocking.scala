package mill.api.daemon

import java.util.concurrent.ConcurrentHashMap
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

  // Never evicted; bounded by the number of distinct lock keys (tasks + meta-build depths)
  private val lockTable = new ConcurrentHashMap[String, ReentrantReadWriteLock]()

  /** Maximum number of per-run files to retain for debugging. */
  private val maxRetainedPerRunFiles = 30

  /**
   * Clean up old per-run files in `dir`, keeping the most recent [[maxRetainedPerRunFiles]]
   * by name (which sorts chronologically since suffixes are millisecond timestamps).
   */
  private def cleanupOldPerRunFiles(dir: os.Path): Unit = {
    try {
      if (!os.exists(dir)) return
      // Match files with a millisecond-timestamp suffix (13+ digits)
      val perRunFiles = os.list(dir).filter(_.last.matches(".*-\\d{13,}(\\.json)?"))

      if (perRunFiles.size > maxRetainedPerRunFiles) {
        val toRemove = perRunFiles.sortBy(_.last).dropRight(maxRetainedPerRunFiles)
        toRemove.foreach { file =>
          try os.remove(file)
          catch { case _: Throwable => }
        }
      }
    } catch {
      case _: Throwable => // best-effort cleanup
    }
  }

  final class InProcessManager(
      out: os.Path,
      override val noBuildLock: Boolean,
      override val noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String = s"${System.currentTimeMillis()}"
    override val consoleTail: os.Path = out / s"mill-console-tail-${runId}"

    // Clean up old per-run files on construction, keeping the most recent sets
    cleanupOldPerRunFiles(out)

    private def perRunPath(default: os.Path): os.Path = {
      val name = default.last.stripSuffix(".json")
      default / os.up / s"$name-$runId.json"
    }

    override def profilePath(default: os.Path): os.Path = perRunPath(default)
    override def chromeProfilePath(default: os.Path): os.Path = perRunPath(default)

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
