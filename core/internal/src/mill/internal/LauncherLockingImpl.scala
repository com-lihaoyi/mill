package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.WaitReporter
import mill.internal.CrossThreadRwLock.HolderInfo

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

private[mill] class LauncherLockingImpl(
    activeCommandMessage: String,
    launcherPid: Long,
    noBuildLock: Boolean,
    noWaitForBuildLock: Boolean,
    lockRegistry: LauncherLockRegistry,
    val runId: String
) extends LauncherLocking {
  private val holder = HolderInfo(launcherPid, activeCommandMessage)
  private val closed = new AtomicBoolean(false)
  private val activeLeases = scala.collection.mutable.Set.empty[LeaseWrapper]
  private val exclusiveWriteCount = new AtomicInteger(0)

  /**
   * Single-slot tracker for the launcher's currently-held [[exclusiveLock]]
   * lease. CAS on [[heldExclusive]] catches reentrant acquisition (the
   * underlying [[CrossThreadRwLock]] is not reentrant); the mutable
   * `underlying` slot lets [[withReleasedExclusive]] swap the underlying
   * lock state out and back in around a nested evaluation while keeping the
   * original lease wrapper valid.
   */
  private class ExclusiveHolder(val kind: LauncherLocking.LockKind) {
    @volatile var underlying: LauncherLocking.Lease = null
  }
  private val heldExclusive = new AtomicReference[ExclusiveHolder](null)

  private def ensureOpen(): Unit =
    if (closed.get()) throw new IllegalStateException(s"Lock session $runId is closed")

  override def metaBuildLock(
      depth: Int,
      kind: LauncherLocking.LockKind,
      waitReporter: WaitReporter
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.metaBuildLock(depth, kind, waitReporter)
    else acquireManagedLease(lockRegistry.metaBuildLockFor(depth).acquire(
      kind,
      waitReporter,
      noWaitForBuildLock,
      holder
    ))
  }

  override def tryMetaBuildWriteLock(depth: Int)
      : Either[LauncherLocking.Contention, LauncherLocking.Lease] = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.tryMetaBuildWriteLock(depth)
    else lockRegistry.metaBuildLockFor(depth).tryAcquireWrite(holder).map(acquireManagedLease)
  }

  override def awaitMetaBuildStateChange(depth: Int, timeoutMs: Long): Unit = {
    ensureOpen()
    if (!noBuildLock) lockRegistry.metaBuildLockFor(depth).awaitStateChange(timeoutMs)
  }

  override def taskLock(
      path: java.nio.file.Path,
      displayLabel: String,
      kind: LauncherLocking.LockKind,
      waitReporter: WaitReporter
  ): LauncherLocking.Lease = {
    ensureOpen()
    // While `exclusiveLock(Write)` is held by this launcher, no peer can run,
    // so per-task locking is unnecessary.
    if (noBuildLock || exclusiveWriteCount.get() > 0) {
      LauncherLocking.Noop.taskLock(path, displayLabel, kind, waitReporter)
    } else {
      val normalized = path.toAbsolutePath.normalize().toString
      val lock = lockRegistry.taskLockFor(normalized, displayLabel)
      acquireManagedLease(lock.acquire(kind, waitReporter, noWaitForBuildLock, holder))
    }
  }

  override def tryTaskWriteLock(
      path: java.nio.file.Path,
      displayLabel: String
  ): Either[LauncherLocking.Contention, LauncherLocking.Lease] = {
    ensureOpen()
    if (noBuildLock || exclusiveWriteCount.get() > 0)
      LauncherLocking.Noop.tryTaskWriteLock(path, displayLabel)
    else {
      val normalized = path.toAbsolutePath.normalize().toString
      lockRegistry.taskLockFor(normalized, displayLabel)
        .tryAcquireWrite(holder)
        .map(acquireManagedLease)
    }
  }

  override def awaitTaskStateChange(
      path: java.nio.file.Path,
      displayLabel: String,
      timeoutMs: Long
  ): Unit = {
    ensureOpen()
    if (!noBuildLock && exclusiveWriteCount.get() == 0) {
      val normalized = path.toAbsolutePath.normalize().toString
      lockRegistry.taskLockFor(normalized, displayLabel).awaitStateChange(timeoutMs)
    }
  }

  override def exclusiveLock(
      kind: LauncherLocking.LockKind,
      waitReporter: WaitReporter
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) return LauncherLocking.Noop.exclusiveLock(kind, waitReporter)
    val ref = new ExclusiveHolder(kind)
    if (!heldExclusive.compareAndSet(null, ref))
      throw new IllegalStateException(
        s"Reentrant exclusiveLock acquisition (runId=$runId); use " +
          "`withReleasedExclusive` to suspend the outer lease around a nested " +
          "re-acquire — the underlying CrossThreadRwLock is not reentrant."
      )
    try ref.underlying =
        lockRegistry.exclusiveLock.acquire(kind, waitReporter, noWaitForBuildLock, holder)
    catch {
      case t: Throwable =>
        heldExclusive.compareAndSet(ref, null)
        throw t
    }
    val isWrite = kind == LauncherLocking.LockKind.Write
    if (isWrite) exclusiveWriteCount.incrementAndGet()
    acquireManagedLease(new LauncherLocking.Lease {
      private val released = new AtomicBoolean(false)
      override def close(): Unit =
        if (released.compareAndSet(false, true)) {
          if (isWrite) exclusiveWriteCount.decrementAndGet()
          val u = ref.underlying
          ref.underlying = null
          heldExclusive.compareAndSet(ref, null)
          if (u != null) u.close()
        }
    })
  }

  override def withReleasedExclusive[T](waitReporter: WaitReporter)(body: => T): T = {
    ensureOpen()
    val ref = heldExclusive.get()
    if (noBuildLock || ref == null || ref.underlying == null) return body
    // Free the slot so a nested `exclusiveLock` can acquire its own; restore
    // `ref.underlying` below so the original lease's `close()` releases the
    // re-acquired lock.
    val savedKind = ref.kind
    val savedWasWrite = savedKind == LauncherLocking.LockKind.Write
    val u = ref.underlying
    ref.underlying = null
    heldExclusive.set(null)
    if (savedWasWrite) exclusiveWriteCount.decrementAndGet()
    u.close()
    try body
    finally {
      ref.underlying =
        lockRegistry.exclusiveLock.acquire(savedKind, waitReporter, noWaitForBuildLock, holder)
      heldExclusive.set(ref)
      if (savedWasWrite) exclusiveWriteCount.incrementAndGet()
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
