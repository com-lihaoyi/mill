package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.internal.CrossThreadRwLock.HolderInfo

import java.io.PrintStream
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

private[mill] class LauncherLockingImpl(
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
  private val exclusiveWriteCount = new AtomicInteger(0)

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
    // If this launcher already holds `exclusiveLock(Write)` no other launcher
    // can be running, so per-task serialization is unnecessary. Returning Noop
    // also avoids self-deadlock when nested `evaluator.execute(...)` from
    // inside an exclusive command tries to escalate a taskLock that the outer
    // evaluation has already retained as Read; `CrossThreadRwLock` is not
    // reentrant so a same-thread Read→Write upgrade would block forever.
    if (noBuildLock || exclusiveWriteCount.get() > 0) {
      LauncherLocking.Noop.taskLock(path, displayLabel, kind)
    } else {
      val normalized = path.toAbsolutePath.normalize().toString
      val lock = lockRegistry.taskLockFor(normalized, displayLabel)
      acquireManagedLease(lock.acquire(kind, waitingErr, noWaitForBuildLock, holder))
    }
  }

  override def exclusiveLock(kind: LauncherLocking.LockKind): LauncherLocking.Lease = {
    ensureOpen()
    // Reentry: if this launcher already holds a Write lease on `exclusiveLock`,
    // any further acquisition (Read or Write) is a no-op — the outer Write
    // already covers all nested work, and re-acquiring would self-deadlock
    // since `CrossThreadRwLock` is not reentrant.
    if (noBuildLock || exclusiveWriteCount.get() > 0) LauncherLocking.Noop.exclusiveLock(kind)
    else {
      val underlying =
        lockRegistry.exclusiveLock.acquire(kind, waitingErr, noWaitForBuildLock, holder)
      val tracked =
        if (kind != LauncherLocking.LockKind.Write) underlying
        else {
          exclusiveWriteCount.incrementAndGet()
          new LauncherLocking.Lease {
            private val released = new AtomicBoolean(false)
            private def releaseWriteCount(): Unit =
              if (released.compareAndSet(false, true)) exclusiveWriteCount.decrementAndGet()
            override def downgradeToRead(): Unit = {
              underlying.downgradeToRead()
              releaseWriteCount()
            }
            override def close(): Unit = {
              try underlying.close()
              finally releaseWriteCount()
            }
          }
        }
      acquireManagedLease(tracked)
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

  private class LeaseWrapper(
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
