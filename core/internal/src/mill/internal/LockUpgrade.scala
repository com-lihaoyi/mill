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
   *  3. If `tryAcquireWrite` returns [[scala.Left]] (lock contended),
   *     `awaitStateChange` for a bounded interval, then loop back to
   *     step 1.
   *
   * Non-blocking try-Write turns long bounded waits into fast cache-hit
   * re-probes: when launcher A is publishing under Write and B wants to
   * compute the same task, B's blocking-Write would wait for A's
   * publish AND for A's downgraded Read (retained for A's downstreams).
   * With try-Write + retry, B fails its Write attempt, then re-acquires
   * Read (which shares with A's Read), and `readBody` sees the
   * just-published cache and returns [[Decision.Complete]] — no Write
   * needed. The bounded `awaitStateChange` keeps the retry cheap: the
   * lock `notifyAll`s on every close/downgrade, so retries fire exactly
   * when a re-probe might newly succeed.
   *
   * `tryAcquireWrite` MUST NOT register the caller as a queued writer
   * (i.e., must not increment any waiter counter that gates Read
   * acquisition); otherwise the caller's own next Read attempt would
   * trip writer-priority and the retry loop would stall.
   */
  def readThenWrite[T](
      acquireRead: => LauncherLocking.Lease,
      tryAcquireWrite: () => Either[LauncherLocking.Contention, LauncherLocking.Lease],
      awaitStateChange: Long => Unit,
      waitReporter: LauncherLocking.WaitReporter,
      awaitTimeoutMs: Long = 1000L
  )(
      readBody: Scope => Decision[T]
  )(
      writeBody: Scope => T
  ): T = {
    // One wait token across all retries: re-opening per failed try
    // would flicker the prompt-detail line.
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
              case Left(LauncherLocking.Contention(message, label, syntheticPrefix)) =>
                if (waitToken == null)
                  waitToken = waitReporter.reportWait(message, label, syntheticPrefix)
                awaitStateChange(awaitTimeoutMs)
                loop()
            }
        }
      }

      loop()
    } finally closeWaitToken()
  }
}
