package mill.api.daemon.internal

import java.nio.file.Path

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

  enum ReadThenWrite[+T] {
    case Complete(value: T)
    case Escalate
  }

  final class LeaseScope private[LauncherLocking] (val lease: Lease) {
    private var retained = false

    def retain(): Lease = {
      retained = true
      lease
    }

    def downgradeAndRetain(): Lease = {
      lease.downgradeToRead()
      retain()
    }

    private[LauncherLocking] def isRetained: Boolean = retained
  }

  final case class HolderInfo(pid: Long, command: String)

  trait Lease extends AutoCloseable {
    def downgradeToRead(): Unit = ()
  }

  def withReadThenWrite[T](
      acquireRead: => Lease,
      acquireWrite: => Lease
  )(
      readBody: LeaseScope => ReadThenWrite[T]
  )(
      writeBody: LeaseScope => T
  ): T = {
    val readScope = new LeaseScope(acquireRead)
    var readClosed = false
    try {
      readBody(readScope) match {
        case ReadThenWrite.Complete(value) =>
          if (!readScope.isRetained) {
            readScope.lease.close()
            readClosed = true
          }
          value
        case ReadThenWrite.Escalate =>
          if (readScope.isRetained)
            throw new IllegalStateException(
              "Cannot retain a read lease and then escalate to write"
            )
          readScope.lease.close()
          readClosed = true
          val writeScope = new LeaseScope(acquireWrite)
          try {
            val value = writeBody(writeScope)
            if (!writeScope.isRetained) writeScope.lease.close()
            value
          } catch {
            case t: Throwable =>
              if (!writeScope.isRetained) writeScope.lease.close()
              throw t
          }
      }
    } catch {
      case t: Throwable =>
        if (!readClosed && !readScope.isRetained) readScope.lease.close()
        throw t
    }
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
