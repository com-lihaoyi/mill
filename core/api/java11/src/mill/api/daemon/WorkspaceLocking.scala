package mill.api.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  case class Resource(key: String, kind: LockKind)

  trait Manager extends AutoCloseable {
    def runId: String
    def consoleTail: os.Path
    def noBuildLock: Boolean
    def noWaitForBuildLock: Boolean
    def withLocks[T](resources: Seq[Resource])(t: => T): T
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
    override def withLocks[T](resources: Seq[Resource])(t: => T): T = t
    override def close(): Unit = ()
  }

  private val nextRunId = new AtomicLong(1L)
  private val lockTable = new ConcurrentHashMap[String, ReentrantReadWriteLock]()

  final class InProcessManager(
      out: os.Path,
      override val noBuildLock: Boolean,
      override val noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String = s"inproc-${nextRunId.getAndIncrement()}"
    override val consoleTail: os.Path = out / "mill-console-tail"

    override def close(): Unit = ()

    override def withLocks[T](resources: Seq[Resource])(t: => T): T = {
      if (noBuildLock || resources.isEmpty) t
      else {
        val sorted = resources.distinct.sortBy(r => (r.key, r.kind.toString))
        val acquired = scala.collection.mutable.Buffer.empty[Resource]
        try {
          sorted.foreach { resource =>
            acquire(resource)
            acquired += resource
          }
          t
        } finally {
          acquired.reverseIterator.foreach { resource =>
            release(resource)
          }
        }
      }
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
