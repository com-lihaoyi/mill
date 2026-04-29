package mill.api.daemon.internal

import java.nio.file.Path

/**
 * Locking APIs that let multiple launchers run concurrently. Acquisition order:
 * [[metaBuildLock]] -> [[exclusiveLock]] -> [[taskLock]] (outer to inner).
 */
private[mill] trait LauncherLocking extends AutoCloseable {

  /**
   * Per-meta-build-depth lock. Read-then-Write upgrade during bootstrap; Read
   * lease is retained for the rest of the launcher run after the frame is
   * published, so concurrent launchers share the same frame via Reads.
   */
  def metaBuildLock(depth: Int, kind: LauncherLocking.LockKind): LauncherLocking.Lease

  /**
   * Per-task-`dest` lock. Read-then-Write upgrade on cache miss; Read lease is
   * retained via `Execution.LeaseTracker` until all transitive downstream
   * terminals complete, so concurrent launchers cannot overwrite outputs that
   * another launcher's downstream is still reading.
   */
  def taskLock(
      path: Path,
      displayLabel: String,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease

  /**
   * Daemon-wide lock taken by each task batch. Normal batches take Read so
   * they overlap; `Task.Command(exclusive = true)` batches take Write so they
   * run alone. Tasks that mutate in-tree sources (e.g. `build.mill`) MUST be
   * declared `exclusive = true` — there is no per-source lock.
   */
  def exclusiveLock(kind: LauncherLocking.LockKind): LauncherLocking.Lease
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
    override def exclusiveLock(kind: LockKind): Lease = NoopLease
    override def close(): Unit = ()
  }
}
