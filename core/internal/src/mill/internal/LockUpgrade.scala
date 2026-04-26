package mill.internal

import mill.api.daemon.internal.LauncherLocking

private[mill] object LockUpgrade {
  sealed trait Decision[+T]
  object Decision {
    final case class Complete[T](value: T) extends Decision[T]
    case object Escalate extends Decision[Nothing]
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
      acquireRead: => LauncherLocking.Lease,
      acquireWrite: => LauncherLocking.Lease
  )(
      readBody: Scope => Decision[T]
  )(
      writeBody: Scope => T
  ): T = {
    val readScope = new Scope(acquireRead)
    var readClosed = false

    try {
      readBody(readScope) match {
        case Decision.Complete(value) =>
          readScope.closeIfUnretained()
          readClosed = true
          value
        case Decision.Escalate =>
          if (readScope.isRetained)
            throw new IllegalStateException(
              "Cannot retain a read lease and then escalate to write"
            )
          readScope.lease.close()
          readClosed = true

          val writeScope = new Scope(acquireWrite)
          try {
            val value = writeBody(writeScope)
            writeScope.closeIfUnretained()
            value
          } catch {
            case t: Throwable =>
              writeScope.closeIfUnretained()
              throw t
          }
      }
    } catch {
      case t: Throwable =>
        if (!readClosed) readScope.closeIfUnretained()
        throw t
    }
  }
}
