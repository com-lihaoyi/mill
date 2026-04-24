package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.{HolderInfo, Lease, LockKind}
import mill.constants.DaemonFiles

/**
 * A small fair read/write lock with lease-based ownership rather than thread-based ownership.
 *
 * We cannot use `java.util.concurrent.locks.ReentrantReadWriteLock` here because Mill acquires
 * task/meta-build locks on worker threads, may downgrade them to read locks, and then retains
 * those read leases until later cleanup on a different thread. The standard library RW locks
 * tie lock ownership to the acquiring thread, while this lock ties ownership to the returned
 * lease object instead.
 */
private[mill] final class FairRwLock(label: String) {
  private val monitor = new Object
  private var readerCount = 0
  private var writerActive = false
  private var waitingWriters = 0
  // Most recent holder(s); used only to describe who is blocking us in waiting
  // messages. Cleared opportunistically once the lock becomes fully idle.
  private var lastHolder: Option[HolderInfo] = None

  private def canAcquireRead: Boolean = !writerActive && waitingWriters == 0
  private def canAcquireWrite: Boolean = !writerActive && readerCount == 0

  private def waitingMessage(blocker: Option[HolderInfo]): String = blocker match {
    case Some(h) =>
      s"Another Mill command in the current daemon is running '${h.command}' with PID ${h.pid}"
    case None =>
      s"Another Mill command in the current daemon is using '$label'"
  }

  def acquire(
      kind: LockKind,
      waitingErr: java.io.PrintStream,
      noWait: Boolean,
      acquirer: HolderInfo
  ): Lease = {
    val isWrite = kind == LockKind.Write

    // Phase 1: either reserve immediately (if free) or register as a waiting
    // writer. Doing both under the same monitor acquisition eliminates the race
    // where two concurrent writers both observe "available" and neither
    // registers as a waiter.
    val reservedImmediately = monitor.synchronized {
      val available = if (isWrite) canAcquireWrite else canAcquireRead
      if (available) {
        if (isWrite) writerActive = true else readerCount += 1
        lastHolder = Some(acquirer)
        true
      } else if (noWait) {
        // --no-wait failures surface up to the user, so include the contested
        // resource label in addition to the blocking launcher's identity.
        throw new Exception(
          s"${waitingMessage(lastHolder)} (resource: '$label') and --no-wait was set, failing"
        )
      } else {
        if (isWrite) waitingWriters += 1
        val blocker = lastHolder
        waitingErr.println(
          s"${waitingMessage(blocker)}, waiting for it to be done... " +
            s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
        )
        false
      }
    }

    // Phase 2 (only if we didn't reserve): wait until acquirable, then reserve.
    if (!reservedImmediately) monitor.synchronized {
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
      lastHolder = Some(acquirer)
    }

    newLease(initiallyWrite = isWrite)
  }

  private def newLease(initiallyWrite: Boolean): Lease = new Lease {
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
        if (readerCount == 0 && !writerActive) lastHolder = None
        monitor.notifyAll()
      }
    }
  }
}
