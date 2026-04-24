package mill.api.daemon.internal

import java.nio.file.Path

/**
 * A per-run handle into workspace locking. Locks are shared across concurrent
 * launchers in the same daemon; one [[LauncherLocking]] instance represents a
 * single run's point of access to them.
 */
private[mill] trait LauncherLocking extends AutoCloseable {
  def metaBuildLock(kind: LauncherLocking.LockKind): LauncherLocking.Lease
  def taskLock(path: Path, kind: LauncherLocking.LockKind): LauncherLocking.Lease
  def withSelectiveExecutionLock[T](@scala.annotation.unused path: Path)(t: => T): T = t
}

private[mill] object LauncherLocking {
  enum LockKind {
    case Read, Write
  }

  /**
   * A held read or write acquisition. `downgradeToRead()` is a no-op on read leases
   * and on already-closed leases; callers can call it unconditionally without
   * tracking whether the lease is currently write-held.
   */
  trait Lease extends AutoCloseable {
    def downgradeToRead(): Unit = ()
  }

  /**
   * Non-locking implementation used in non-daemon mode where cross-launcher
   * coordination is not supported.
   */
  object Noop extends LauncherLocking {
    private object NoopLease extends Lease {
      override def close(): Unit = ()
    }
    override def metaBuildLock(kind: LockKind): Lease = NoopLease
    override def taskLock(path: Path, kind: LockKind): Lease = NoopLease
    override def close(): Unit = ()
  }
}
