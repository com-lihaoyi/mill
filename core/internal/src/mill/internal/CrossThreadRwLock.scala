package mill.internal

import mill.api.MillException
import mill.api.daemon.internal.LauncherLocking.{Lease, LockKind}
import mill.constants.DaemonFiles

/**
 * A version of [[java.util.concurrent.locks.ReadWriteLock]] that can be acquired
 * and released on separate threads. `label` is the human-readable name used in
 * waiting/blocking messages; uniqueness across locks is the registry's
 * responsibility (e.g. [[LauncherLockRegistry]]'s map key), not the lock's.
 */
class CrossThreadRwLock(label: String) {
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

  private def waitingMessage(blocker: Option[HolderInfo]): String = blocker match {
    case Some(h) =>
      s"Another Mill command in the current daemon is running '${h.command}' task '$label' with PID ${h.pid}"
    case None =>
      s"Another Mill command in the current daemon is using task '$label'"
  }

  def acquire(
      kind: LockKind,
      waitingErr: java.io.PrintStream,
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
        throw new MillException(
          s"${waitingMessage(currentBlocker())} and --no-wait was set, failing"
        )
      } else {
        if (isWrite) waitingWriters += 1
        None
      }
    }

    leaseOpt.getOrElse {
      val blocker = monitor.synchronized(currentBlocker())
      waitingErr.println(
        s"${waitingMessage(blocker)}, waiting for it " +
          s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
      )

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
    }
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
