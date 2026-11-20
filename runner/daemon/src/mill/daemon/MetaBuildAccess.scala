package mill.daemon

import mill.api.daemon.internal.LauncherLocking
import mill.internal.LockUpgrade

import java.util.concurrent.atomic.AtomicReference

/**
 * Combines the daemon-shared [[RunnerSharedState]] with the meta-build
 * read/write lock so callers cannot read or update the shared state without
 * going through the lock.
 *
 *   - [[withMetaBuild]] runs the read-then-write bootstrap dance for one
 *     meta-build level: take a read lock, run `probe`; if it returns
 *     [[LockUpgrade.Decision.Escalate]], upgrade to a write lock and run
 *     `evaluate`. Both bodies receive a snapshot of the shared state and a
 *     [[LockUpgrade.Scope]] they can use to retain the lease as a read lease.
 *     The write body additionally receives an `update` callback that
 *     atomically mutates the state, returning the displaced previous state
 *     for resource cleanup.
 *
 *   - [[snapshot]] is a lock-free read used in places where consistency
 *     under concurrent updates is not required (e.g. reading immutable
 *     bootstrap fields after a higher-level read lock has already been
 *     taken). Each call site documents why it is safe.
 */
private[daemon] class MetaBuildAccess(
    private val ref: AtomicReference[RunnerSharedState],
    val workspaceLocking: LauncherLocking
) {

  /** Lock-free read. See class doc for when to use. */
  def snapshot(): RunnerSharedState = ref.get()

  /**
   * Atomically replace the daemon-wide user-level (depth = 0) `moduleWatched`.
   * Bypasses the meta-build lock because this field is not coupled to any
   * classloader/worker lifetime — the next launcher's depth-1 reusable check
   * just reads it as a "did the outer inputs change since last run" signal.
   */
  def publishUserFinalModuleWatched(moduleWatched: Seq[mill.api.daemon.Watchable]): Unit = {
    val _ = ref.updateAndGet(_.withUserFinalModuleWatched(moduleWatched))
  }

  /**
   * Read-then-write meta-build lock dance at `depth`.
   *
   * `probe` runs under a read lock; if it returns [[LockUpgrade.Decision.Complete]]
   * the read lease may be retained via the supplied scope. If it returns
   * [[LockUpgrade.Decision.Escalate]] the read lock is released, a write
   * lock is acquired, and `evaluate` runs with a [[MetaBuildAccess.WriteScope]]
   * which exposes both the lock scope and an `update` callback for atomic
   * state mutation.
   */
  def withMetaBuild[T](depth: Int, waitReporter: LauncherLocking.WaitReporter)(
      probe: (RunnerSharedState, LockUpgrade.Scope) => LockUpgrade.Decision[T]
  )(
      evaluate: MetaBuildAccess.WriteScope => T
  ): T = {
    LockUpgrade.readThenWrite(
      acquireRead =
        workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Read, waitReporter),
      // With many launchers probing concurrently, some reader is almost always
      // active, so a non-queued try-Write can fail forever while nobody
      // publishes. After a few failures, queue for Write instead: that holds
      // back new probes, which cannot complete before someone publishes anyway.
      // Queuing cannot deadlock: a launcher holding a meta-build lease at this
      // depth never needs a new one at this depth or a deeper one.
      tryAcquireWrite = {
        var failedWriteTries = 0
        () =>
          workspaceLocking.tryMetaBuildWriteLock(depth).left.flatMap { contention =>
            failedWriteTries += 1
            if (failedWriteTries < MetaBuildAccess.failedWriteTriesBeforeQueuing) Left(contention)
            else Right(workspaceLocking.metaBuildLock(
              depth,
              LauncherLocking.LockKind.Write,
              // `readThenWrite` has been reporting the wait since the first failed try
              LauncherLocking.WaitReporter.Noop
            ))
          }
      },
      awaitStateChange =
        timeoutMs => workspaceLocking.awaitMetaBuildStateChange(depth, timeoutMs),
      waitReporter = waitReporter
    )(scope => probe(ref.get(), scope)) { scope =>
      evaluate(MetaBuildAccess.WriteScope(ref, scope))
    }
  }
}

private[daemon] object MetaBuildAccess {

  /**
   * Without contention a launcher fails at most once or twice; in a livelock
   * it fails hundreds of times.
   */
  val failedWriteTriesBeforeQueuing = 3

  /**
   * Held during the write phase of [[MetaBuildAccess.withMetaBuild]]. All
   * shared-state mutations during the bootstrap of a meta-build level go
   * through this scope so they happen under the meta-build write lock.
   */
  class WriteScope private[MetaBuildAccess] (
      private val ref: AtomicReference[RunnerSharedState],
      val scope: LockUpgrade.Scope
  ) {

    /** Current shared state under the held write lock. */
    def snapshot(): RunnerSharedState = ref.get()

    /**
     * Atomically replace the shared state with `f(current)`. Returns the
     * previous (now-displaced) state so the caller can reclaim resources
     * (e.g. close displaced classloaders).
     */
    def update(f: RunnerSharedState => RunnerSharedState): RunnerSharedState =
      ref.getAndUpdate(f.apply)
  }
}
