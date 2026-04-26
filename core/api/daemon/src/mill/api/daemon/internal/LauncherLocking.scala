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

  final case class HolderInfo(pid: Long, command: String)

  trait Lease extends AutoCloseable {
    def downgradeToRead(): Unit = ()
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
