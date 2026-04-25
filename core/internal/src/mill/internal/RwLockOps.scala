package mill.internal

import mill.api.daemon.internal.LauncherLocking.{Lease, LockKind}

/**
 * Combinators for the standard read-speculate / write-on-miss / downgrade
 * dance that callers of [[WriterPreferringRwLock]] use to coordinate cache
 * lookups against potentially-racing writers. The lock deliberately omits
 * an in-place upgrade primitive (it would deadlock as soon as two readers
 * attempted upgrade simultaneously); these helpers encode the canonical
 * release-read / acquire-write / re-check-cache pattern instead.
 */
private[mill] object RwLockOps {

  /**
   * Result of a [[speculateReadElseWrite]] body. Carries the value and an
   * optional read lease that the caller is expected to either retain (so that
   * downstream consumers see a stable cache entry) or close immediately.
   */
  final case class Outcome[T](value: T, retainedRead: Option[Lease])

  /**
   * Read-first speculation against a [[LauncherLocking]] lock with deferred
   * write acquisition.
   *
   *   1. Acquire a read lease on `acquire(LockKind.Read)`.
   *   2. Run `speculate` under the read lease. If it returns `Some(t)`, retain
   *      the read lease (caller decides what to do with it via the
   *      [[Outcome.retainedRead]] field) and return `t`.
   *   3. Otherwise, close the read lease, acquire a fresh write lease, run
   *      `recheck` (typically the same speculation, allowing a sibling writer
   *      that completed between our read-close and write-acquire to be
   *      observed). If `recheck` returns `Some(t)`, downgrade the write to
   *      read, retain it, return `t`.
   *   4. Else run `write` under the write lease, downgrade to read on success,
   *      and return its result. On exception the write lease is closed.
   *
   * `acquire` is a function rather than a [[WriterPreferringRwLock]] directly
   * so callers can plumb through the higher-level [[LauncherLocking]] handle
   * (which also tracks per-session lifecycle) without exposing the underlying
   * lock primitive.
   *
   * The function takes a `retainOnSuccess` hook: callers that already know
   * whether the produced value should pin the read lease for downstream
   * consumers (e.g. successful task results) can return `Some(true)` from
   * `write`; failures return `Some(false)` and we close instead of retaining.
   */
  def speculateReadElseWrite[T](
      acquire: LockKind => Lease
  )(speculate: => Option[T])(write: => (T, Boolean)): Outcome[T] = {
    val readLease = acquire(LockKind.Read)
    val speculated =
      try speculate
      catch {
        case e: Throwable =>
          readLease.close()
          throw e
      }

    speculated match {
      case Some(value) =>
        Outcome(value, Some(readLease))
      case None =>
        readLease.close()
        val writeLease = acquire(LockKind.Write)
        try {
          speculate match {
            case Some(value) =>
              writeLease.downgradeToRead()
              Outcome(value, Some(writeLease))
            case None =>
              val (value, retain) =
                try write
                catch {
                  case e: Throwable =>
                    writeLease.close()
                    throw e
                }
              if (retain) {
                writeLease.downgradeToRead()
                Outcome(value, Some(writeLease))
              } else {
                writeLease.close()
                Outcome(value, None)
              }
          }
        } catch {
          case e: Throwable =>
            try writeLease.close() catch { case _: Throwable => () }
            throw e
        }
    }
  }
}
