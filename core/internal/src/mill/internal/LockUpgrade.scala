package mill.internal

import mill.api.daemon.internal.LauncherLocking

/**
 * Helpers for the common "check under a read lock, then optionally re-run under
 * a write lock" pattern.
 */
object LockUpgrade {
  enum Decision[+T] {
    case Complete(value: T)
    case Escalate
  }

  class Scope private[LockUpgrade] (val lease: LauncherLocking.Lease) {
    private var retained = false

    def retain(): LauncherLocking.Lease = {
      retained = true
      lease
    }

    def downgradeAndRetain(): LauncherLocking.Lease = {
      lease.downgradeToRead()
      retain()
    }

    private[LockUpgrade] def closeIfUnretained(): Unit =
      if (!retained) lease.close()

    private[LockUpgrade] def closeAlways(): Unit =
      lease.close()

    private[LockUpgrade] def isRetained: Boolean = retained
  }

  /**
   * Retryable read-then-write upgrade.
   *
   *  1. Acquire Read, run `readBody`. If [[Decision.Complete]], return.
   *  2. If [[Decision.Escalate]], release Read and try Write
   *     non-blocking (`tryAcquireWrite`). If that succeeds, run
   *     `writeBody` under the Write lease and return.
   *  3. If `tryAcquireWrite` returns [[scala.None]] (lock contended),
   *     `awaitStateChange` for a bounded interval, then loop back to
   *     step 1.
   *
   * The non-blocking try-Write avoids the deadlock where launcher B
   * queues for Write behind launcher A, and A — after publishing —
   * downgrades to a long-held Read that B's queued Write would
   * otherwise block on indefinitely. By re-probing under Read on every
   * retry, B sees A's published frame and Completes via the read path
   * instead of needing Write at all. The bounded `awaitStateChange`
   * (rather than busy polling) keeps the retry cheap: the lock
   * `notifyAll`s on every close/downgrade, so retries fire exactly
   * when a re-probe might newly succeed.
   *
   * `tryAcquireWrite` MUST NOT register the caller as a queued writer
   * (i.e., must not increment any waiter counter that gates Read
   * acquisition); otherwise the caller's own next Read attempt would be
   * blocked by writer-priority and the deadlock would re-emerge.
   */
  def readThenWrite[T](
      acquireRead: => LauncherLocking.Lease,
      tryAcquireWrite: () => Either[String, LauncherLocking.Lease],
      awaitStateChange: Long => Unit,
      waitReporter: LauncherLocking.WaitReporter,
      awaitTimeoutMs: Long = 1000L
  )(
      readBody: Scope => Decision[T]
  )(
      writeBody: Scope => T
  ): T = {
    // Single wait token that survives across retry iterations; opened on
    // the first failed try-Write and closed when we exit (Read fast-path
    // Completed, or Write succeeded). Closed-then-reopened on each failed
    // try would flicker the prompt-detail line; one open/close keeps the
    // wait status stable for the duration of the contention.
    var waitToken: AutoCloseable = null
    def closeWaitToken(): Unit = if (waitToken != null) {
      try waitToken.close()
      catch { case _: Throwable => () }
      waitToken = null
    }

    try {
      @scala.annotation.tailrec
      def loop(): T = {
        val readScope = new Scope(acquireRead)
        var readClosed = false
        val readDecision: Decision[T] =
          try {
            val d = readBody(readScope)
            d match {
              case Decision.Complete(_) =>
                readScope.closeIfUnretained()
                readClosed = true
              case Decision.Escalate =>
                if (readScope.isRetained)
                  throw new IllegalStateException(
                    "Cannot retain a read lease and then escalate to write"
                  )
                readScope.closeAlways()
                readClosed = true
            }
            d
          } catch {
            case t: Throwable =>
              if (!readClosed) readScope.closeAlways()
              throw t
          }

        readDecision match {
          case Decision.Complete(value) => value
          case Decision.Escalate =>
            tryAcquireWrite() match {
              case Right(writeLease) =>
                val writeScope = new Scope(writeLease)
                try {
                  val value = writeBody(writeScope)
                  writeScope.closeIfUnretained()
                  value
                } catch {
                  case t: Throwable =>
                    writeScope.closeAlways()
                    throw t
                }
              case Left(blockMsg) =>
                if (waitToken == null) waitToken = waitReporter.reportWait(blockMsg)
                awaitStateChange(awaitTimeoutMs)
                loop()
            }
        }
      }

      loop()
    } finally closeWaitToken()
  }
}
