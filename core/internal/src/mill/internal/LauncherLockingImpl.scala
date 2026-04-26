package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.HolderInfo

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

private[mill] final class LauncherLockingImpl(
    activeCommandMessage: String,
    launcherPid: Long,
    waitingErr: PrintStream,
    noBuildLock: Boolean,
    noWaitForBuildLock: Boolean,
    lockRegistry: LauncherLockRegistry,
    val runId: String
) extends LauncherLocking {
  private val holder = HolderInfo(launcherPid, activeCommandMessage)
  private val closed = new AtomicBoolean(false)
  private val activeLeases = scala.collection.mutable.Set.empty[LeaseWrapper]

  private def ensureOpen(): Unit =
    if (closed.get()) throw new IllegalStateException(s"Lock session $runId is closed")

  override def metaBuildLock(
      depth: Int,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.metaBuildLock(depth, kind)
    else acquireManagedLease(lockRegistry.metaBuildLockFor(depth).acquire(
      kind,
      waitingErr,
      noWaitForBuildLock,
      holder
    ))
  }

  override def taskLock(
      path: java.nio.file.Path,
      displayLabel: String,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.taskLock(path, displayLabel, kind)
    else {
      val normalized = path.toAbsolutePath.normalize().toString
      val lock = lockRegistry.taskLockFor(normalized, displayLabel)
      acquireManagedLease(lock.acquire(kind, waitingErr, noWaitForBuildLock, holder))
    }
  }

  private def acquireManagedLease(
      underlying: LauncherLocking.Lease
  ): LauncherLocking.Lease = {
    val lease = new LeaseWrapper(underlying)
    val accepted = activeLeases.synchronized {
      if (closed.get()) false
      else {
        activeLeases += lease
        true
      }
    }
    if (!accepted) {
      try underlying.close()
      catch { case _: Throwable => () }
      throw new IllegalStateException(s"Lock session $runId is closed")
    }
    lease
  }

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      val leases = activeLeases.synchronized(activeLeases.toSeq)
      leases.foreach(lease =>
        try lease.close()
        catch { case _: Throwable => () }
      )
    }

  private final class LeaseWrapper(
      underlying: LauncherLocking.Lease
  ) extends LauncherLocking.Lease {
    private val closed = new AtomicBoolean(false)

    override def downgradeToRead(): Unit =
      if (!closed.get()) underlying.downgradeToRead()

    override def close(): Unit =
      if (closed.compareAndSet(false, true)) {
        underlying.close()
        activeLeases.synchronized(activeLeases -= this)
      }
  }
}
