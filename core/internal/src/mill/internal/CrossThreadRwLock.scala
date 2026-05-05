package mill.internal

import mill.api.MillException
import mill.api.daemon.internal.LauncherLocking.{Contention, Lease, LockKind, WaitReporter}

/**
 * A version of [[java.util.concurrent.locks.ReadWriteLock]] that can be acquired
 * and released on separate threads. `label` is the human-readable name used in
 * waiting/blocking messages; uniqueness across locks is the registry's
 * responsibility (e.g. [[LauncherLockRegistry]]'s map key), not the lock's.
 */
class CrossThreadRwLock(
    label: String,
    showLabelInMessage: Boolean = true,
    syntheticPrefix: Seq[String] = Nil
) {
  import CrossThreadRwLock.HolderInfo

  private val monitor = new Object
  private var readerCount = 0
  private var writerActive = false
  private var waitingWriters = 0
  private val holders = new java.util.LinkedHashMap[Lease, HolderInfo]()

  private def canAcquireRead: Boolean = !writerActive && waitingWriters == 0
  private def canAcquireWrite: Boolean = !writerActive && readerCount == 0

  private def currentBlocker(): Option[HolderInfo] = {
    val it = holders.values().iterator()
    if (it.hasNext) Some(it.next()) else None
  }

  private def waitingMessage(blocker: Option[HolderInfo], kind: LockKind): String = {
    val kindStr = kind match { case LockKind.Read => "read"; case LockKind.Write => "write" }
    val labelToken = if (showLabelInMessage) s" '$label'" else ""
    blocker match {
      case Some(h) =>
        s"blocked on $kindStr lock$labelToken command '${h.command}' PID ${h.pid}"
      case None => s"blocked on $kindStr lock$labelToken"
    }
  }

  def acquire(
      kind: LockKind,
      waitReporter: WaitReporter,
      noWait: Boolean,
      acquirer: HolderInfo
  ): Lease = {
    val isWrite = kind == LockKind.Write

    val leaseOpt = monitor.synchronized {
      val available = if (isWrite) canAcquireWrite else canAcquireRead
      if (available) {
        if (isWrite) writerActive = true else readerCount += 1
        val lease = newLease(initiallyWrite = isWrite)
        holders.put(lease, acquirer)
        Some(lease)
      } else if (noWait) {
        // Surface as MillException so MillMain0.handleMillException renders a
        // clean user-facing message instead of a stack trace; this is an
        // expected condition when --no-wait collides with another launcher.
        // Force the label into the message: this exception bubbles up
        // standalone, with no surrounding prompt-line prefix to identify
        // the lock.
        val msg = WaitReporter.ensureLabel(waitingMessage(currentBlocker(), kind), label)
        throw new MillException(s"$msg and --no-wait was set, failing")
      } else {
        if (isWrite) waitingWriters += 1
        None
      }
    }

    leaseOpt.getOrElse {
      val blocker = monitor.synchronized(currentBlocker())
      val waitToken =
        waitReporter.reportWait(waitingMessage(blocker, kind), label, syntheticPrefix)

      try {
        monitor.synchronized {
          try {
            while ({
              val available = if (isWrite) canAcquireWrite else canAcquireRead
              !available
            }) monitor.wait()
          } catch {
            case e: Throwable =>
              if (isWrite) {
                waitingWriters -= 1
                monitor.notifyAll()
              }
              throw e
          }
          if (isWrite) waitingWriters -= 1
          if (isWrite) writerActive = true else readerCount += 1
          val lease = newLease(initiallyWrite = isWrite)
          holders.put(lease, acquirer)
          lease
        }
      } finally {
        try waitToken.close()
        catch { case _: Throwable => () }
      }
    }
  }

  /**
   * Non-blocking, non-queued Write attempt. Returns [[Right]] with a
   * Lease if Write is immediately available (no active writer, no
   * readers); [[Left]] with a human-readable description of the current
   * blocker otherwise.
   *
   * Crucially: a failed try does NOT increment [[waitingWriters]], so the
   * caller's subsequent Read attempts won't trip writer-priority
   * (`canAcquireRead = !writerActive && waitingWriters == 0`). This is
   * what makes the retryable read-then-write pattern in
   * [[LockUpgrade.readThenWrite]] work: a launcher can fail to grab Write,
   * fall back to Read, and re-probe shared state without poisoning its
   * own next Read attempt.
   */
  def tryAcquireWrite(acquirer: HolderInfo): Either[Contention, Lease] = monitor.synchronized {
    if (canAcquireWrite) {
      writerActive = true
      val lease = newLease(initiallyWrite = true)
      holders.put(lease, acquirer)
      Right(lease)
    } else Left(Contention(
      waitingMessage(currentBlocker(), LockKind.Write),
      label,
      syntheticPrefix
    ))
  }

  /**
   * Block on the lock's monitor for up to `timeoutMs`, returning when any
   * lock state change occurs (close/downgrade `notifyAll`s) or the
   * timeout fires. Used by [[LockUpgrade.readThenWrite]]'s retry loop to
   * sleep efficiently between try-Write attempts: the lock already
   * notifies on every state change, so the retry wakes exactly when a
   * re-probe might newly succeed. The timeout bounds the worst-case
   * sleep and protects against missed notifications.
   */
  def awaitStateChange(timeoutMs: Long): Unit = monitor.synchronized {
    monitor.wait(timeoutMs)
  }

  private def newLease(initiallyWrite: Boolean): Lease = new Lease { self =>
    private var closed = false
    private var readMode = !initiallyWrite

    override def downgradeToRead(): Unit = monitor.synchronized {
      if (!closed && !readMode) {
        writerActive = false
        readerCount += 1
        readMode = true
        monitor.notifyAll()
      }
    }

    override def close(): Unit = monitor.synchronized {
      if (!closed) {
        if (readMode) readerCount -= 1
        else writerActive = false
        closed = true
        holders.remove(self)
        monitor.notifyAll()
      }
    }
  }
}

object CrossThreadRwLock {
  case class HolderInfo(pid: Long, command: String)
}
