package mill.internal

import mill.api.daemon.internal.LauncherLocking

/**
 * Helpers for the common "check under a read lock, then optionally re-run under
 * a write lock" pattern.
 *
 * [[readThenWrite]] acquires a read lease first so callers can cheaply observe
 * shared state and either:
 *
 *  - return a completed result while optionally retaining that read lease, or
 *  - escalate to a write lease (via [[LauncherLocking.Lease.upgradeToWrite]])
 *    to perform the mutating path.
 *
 * The read-to-write transition is seamless: the same lease is upgraded in
 * place, so the resource is never observably unlocked between the two phases.
 * Other launchers cannot interleave a write between the read and write bodies.
 */
object LockUpgrade {
  enum Decision[+T] {
    case Complete(value: T)
    case Escalate
  }

  final class Scope private[LockUpgrade] (val lease: LauncherLocking.Lease) {
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

    private[LockUpgrade] def isRetained: Boolean = retained
  }

  def readThenWrite[T](
      acquireRead: => LauncherLocking.Lease
  )(
      readBody: Scope => Decision[T]
  )(
      writeBody: Scope => T
  ): T = {
    val readScope = new Scope(acquireRead)
    try {
      readBody(readScope) match {
        case Decision.Complete(value) =>
          readScope.closeIfUnretained()
          value
        case Decision.Escalate =>
          if (readScope.isRetained)
            throw new IllegalStateException(
              "Cannot retain a read lease and then escalate to write"
            )
          readScope.lease.upgradeToWrite()
          val writeScope = new Scope(readScope.lease)
          try writeBody(writeScope)
          finally writeScope.closeIfUnretained()
      }
    } catch {
      case t: Throwable =>
        readScope.closeIfUnretained()
        throw t
    }
  }
}
