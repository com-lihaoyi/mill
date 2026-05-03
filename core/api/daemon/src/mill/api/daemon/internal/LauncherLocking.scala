package mill.api.daemon.internal

import java.io.PrintStream
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
  def metaBuildLock(
      depth: Int,
      kind: LauncherLocking.LockKind,
      waitReporter: LauncherLocking.WaitReporter
  ): LauncherLocking.Lease

  /**
   * Non-blocking, non-queued Write attempt on the meta-build lock at
   * `depth`. Returns [[scala.None]] if Write is not immediately
   * available; the caller can then back off, re-probe under Read, and
   * decide whether Write is still needed. Failed tries do NOT register
   * as queued writers, so the caller's subsequent Read attempts are not
   * blocked by writer-priority — this is what makes the retryable
   * read-then-write pattern in [[mill.internal.LockUpgrade.readThenWrite]]
   * work without poisoning the caller's own re-probe.
   */
  def tryMetaBuildWriteLock(depth: Int): Either[String, LauncherLocking.Lease]

  /**
   * Block on the meta-build lock at `depth` for up to `timeoutMs`,
   * returning on the first lock state change (close/downgrade) or
   * timeout. Used by the retryable read-then-write loop to sleep
   * efficiently between try-Write attempts.
   */
  def awaitMetaBuildStateChange(depth: Int, timeoutMs: Long): Unit

  /**
   * Per-task-`dest` lock. Read-then-Write upgrade on cache miss; Read lease is
   * retained via `Execution.LeaseTracker` until all transitive downstream
   * terminals complete, so concurrent launchers cannot overwrite outputs that
   * another launcher's downstream is still reading.
   */
  def taskLock(
      path: Path,
      displayLabel: String,
      kind: LauncherLocking.LockKind,
      waitReporter: LauncherLocking.WaitReporter
  ): LauncherLocking.Lease

  /**
   * Non-blocking, non-queued Write counterpart of [[taskLock]].
   * See [[tryMetaBuildWriteLock]] for semantics.
   */
  def tryTaskWriteLock(
      path: Path,
      displayLabel: String
  ): Either[String, LauncherLocking.Lease]

  /**
   * Bounded await on per-task lock state changes; counterpart of
   * [[awaitMetaBuildStateChange]].
   */
  def awaitTaskStateChange(path: Path, displayLabel: String, timeoutMs: Long): Unit

  /**
   * Daemon-wide lock taken by each task batch. Normal batches take Read so
   * they overlap; `Task.Command(exclusive = true)` batches take Write so they
   * run alone. Tasks that mutate in-tree sources (e.g. `build.mill`) MUST be
   * declared `exclusive = true` — there is no per-source lock.
   */
  def exclusiveLock(
      kind: LauncherLocking.LockKind,
      waitReporter: LauncherLocking.WaitReporter
  ): LauncherLocking.Lease
}

private[mill] object LauncherLocking {
  enum LockKind {
    case Read, Write
  }

  trait Lease extends AutoCloseable {
    def downgradeToRead(): Unit = ()
  }

  /**
   * Surface a "blocked on lock" status to the user when a lock acquisition has
   * to wait. Implementations should display the message in a way that doesn't
   * disturb the active console UI (e.g. via the multi-line prompt's detail
   * line) and clear it when the returned token is closed.
   *
   * Implementations may also respond to holder changes by re-calling
   * `reportWait` — each call replaces the previous wait status.
   */
  trait WaitReporter {
    def reportWait(message: String): AutoCloseable
  }

  object WaitReporter {
    private val NoopToken: AutoCloseable = () => ()

    /** Discards wait events. Useful for tests and noBuildLock. */
    val Noop: WaitReporter = (_: String) => NoopToken

    /**
     * Prints the wait message once on `reportWait` and does nothing on close.
     * Matches the legacy behavior: a one-shot stderr line that scrolls into
     * the user's terminal history. Used when no live prompt is available
     * (early bootstrap, no-daemon, BSP, `--ticker false`, etc.).
     */
    def stderr(stream: PrintStream): WaitReporter = (msg: String) => {
      stream.println(msg)
      NoopToken
    }
  }

  object Noop extends LauncherLocking {
    private object NoopLease extends Lease {
      override def close(): Unit = ()
    }
    override def metaBuildLock(
        depth: Int,
        kind: LockKind,
        waitReporter: WaitReporter
    ): Lease = NoopLease
    override def tryMetaBuildWriteLock(depth: Int): Either[String, Lease] = Right(NoopLease)
    override def awaitMetaBuildStateChange(depth: Int, timeoutMs: Long): Unit = ()
    override def taskLock(
        path: Path,
        displayLabel: String,
        kind: LockKind,
        waitReporter: WaitReporter
    ): Lease = NoopLease
    override def tryTaskWriteLock(path: Path, displayLabel: String): Either[String, Lease] =
      Right(NoopLease)
    override def awaitTaskStateChange(path: Path, displayLabel: String, timeoutMs: Long): Unit = ()
    override def exclusiveLock(kind: LockKind, waitReporter: WaitReporter): Lease = NoopLease
    override def close(): Unit = ()
  }
}
