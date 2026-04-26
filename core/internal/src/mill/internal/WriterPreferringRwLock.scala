package mill.internal

import mill.api.daemon.internal.LauncherLocking.{Lease, LockKind}
import mill.constants.DaemonFiles

private[mill] object WriterPreferringRwLock {
  final case class HolderInfo(pid: Long, command: String)
}

private[mill] final class WriterPreferringRwLock(
    @scala.annotation.unused label: String,
    displayLabel: String = ""
) {
  import WriterPreferringRwLock.HolderInfo

  private val effectiveDisplayLabel: String =
    if (displayLabel.nonEmpty) displayLabel else label
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
      s"Another Mill command in the current daemon is running '${h.command}' with PID ${h.pid}"
    case None =>
      s"Another Mill command in the current daemon is using '$effectiveDisplayLabel'"
  }

  private def blockedSuffix(kind: LockKind): String = kind match {
    case LockKind.Read => s" (blocked on reading from $effectiveDisplayLabel)"
    case LockKind.Write => s" (blocked on writing to $effectiveDisplayLabel)"
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
        throw new Exception(
          s"${waitingMessage(currentBlocker())}${blockedSuffix(kind)} and --no-wait was set, failing"
        )
      } else {
        if (isWrite) waitingWriters += 1
        None
      }
    }

    leaseOpt.getOrElse {
      val blocker = monitor.synchronized(currentBlocker())
      waitingErr.println(
        s"${waitingMessage(blocker)}, waiting for it to be done... " +
          s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)" +
          blockedSuffix(kind)
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
