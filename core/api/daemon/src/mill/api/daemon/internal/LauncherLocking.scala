package mill.api.daemon.internal

import java.nio.file.Path

/**
 * A per-run handle into workspace locking. Locks are shared across concurrent
 * launchers in the same daemon; one [[LauncherLocking]] instance represents a
 * single run's point of access to them. Meta-build locks are keyed by meta-build
 * depth so that a read lease retained at a deeper depth does not block a writer
 * acquiring the shallower depth's lock during the same run.
 */
private[mill] trait LauncherLocking extends AutoCloseable {
  /**
   * Acquire a meta-build lock keyed by `depth`. The bootstrap module install
   * (see [[mill.daemon.MillBuildBootstrap.makeBootstrapState]]) reuses this
   * with the deepest recursion depth — there is no `processRunClasspath`
   * running at that depth, so the lock is unambiguously the bootstrap's.
   */
  def metaBuildLock(depth: Int, kind: LauncherLocking.LockKind): LauncherLocking.Lease

  /**
   * Acquire a per-task lock keyed by `path` (the task's dest dir).
   * `displayLabel` is the human-readable name used in waiting messages
   * (e.g. the task segments string `foo.bar`).
   */
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

  /**
   * Identifies the launcher that acquired a lease, used to compose waiting
   * messages shown to other launchers that block on the same lock.
   */
  final case class HolderInfo(pid: Long, command: String)

  /**
   * A held read or write acquisition. `downgradeToRead()` is a no-op on read leases
   * and on already-closed leases; callers can call it unconditionally without
   * tracking whether the lease is currently write-held.
   *
   * NB: there is intentionally no `upgradeToWrite` primitive. The underlying
   * lock cannot upgrade a held read lease to a write lease without deadlock as
   * soon as two readers attempt it simultaneously, so callers that need to
   * mutate after speculating must close the read lease, acquire a fresh write
   * lease, and re-validate any speculation under the new write. See
   * `mill.daemon.MillBuildBootstrap.processRunClasspath` and
   * `mill.exec.GroupExecution.evaluateTaskWithCaching` for the canonical
   * open-coded versions of this dance.
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
    override def metaBuildLock(depth: Int, kind: LockKind): Lease = NoopLease
    override def taskLock(path: Path, displayLabel: String, kind: LockKind): Lease = NoopLease
    override def close(): Unit = ()
  }
}
