package mill.api.daemon.internal

import java.nio.file.Path

/**
 * Locking APIs to let us safely run multiple concurrent launchers at the same time
 *
 * [[metaBuildLock]] ensures that only one launcher can be bootstrapping the meta-build,
 * but once the meta-build is done multiple launchers can run concurrently and only lock
 * on the individual tasks they need to run or use.
 */
private[mill] trait LauncherLocking extends AutoCloseable {

  def metaBuildLock(depth: Int, kind: LauncherLocking.LockKind): LauncherLocking.Lease

  def taskLock(
      path: Path,
      displayLabel: String,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease
}

private[mill] object LauncherLocking {
  enum LockKind {
    case Read, Write
  }

  trait Lease extends AutoCloseable {
    def downgradeToRead(): Unit = ()
  }

  object Noop extends LauncherLocking {
    private object NoopLease extends Lease {
      override def close(): Unit = ()
    }
    override def metaBuildLock(depth: Int, kind: LockKind): Lease = NoopLease
    override def taskLock(path: Path, displayLabel: String, kind: LockKind): Lease = NoopLease
    override def close(): Unit = ()
  }
}
