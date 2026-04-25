package mill.internal

import mill.api.daemon.internal.LauncherLocking.{HolderInfo, Lease, LockKind}
import mill.constants.DaemonFiles

/**
 * A small writer-preferring read/write lock with lease-based ownership rather than
 * thread-based ownership.
 *
 * We cannot use `java.util.concurrent.locks.ReentrantReadWriteLock` here because Mill acquires
 * task/meta-build locks on worker threads, may downgrade them to read locks, and then retains
 * those read leases until later cleanup on a different thread. The standard library RW locks
 * tie lock ownership to the acquiring thread, while this lock ties ownership to the returned
 * lease object instead.
 *
 * Policy:
 *  - Writer-preferring: while a writer is queued, new readers wait too. Prevents
 *    indefinite reader starvation but is not strict FIFO; among readers/writers
 *    woken by `notifyAll`, JVM scheduling decides who acquires next.
 *  - No in-place upgrade: a held read lease cannot become a write lease. Callers
 *    that need to mutate after speculating must close the read lease, acquire a
 *    fresh write lease, and re-validate any cached state under the write. The
 *    lock deliberately omits a `tryUpgrade` primitive because such an upgrade
 *    would deadlock as soon as two readers attempted it simultaneously. See
 *    [[mill.internal.RwLockOps.speculateReadElseWrite]] for the canonical
 *    helper.
 *  - Downgrade is supported via [[mill.api.daemon.internal.LauncherLocking.Lease.downgradeToRead]].
 */
private[mill] final class WriterPreferringRwLock(label: String) {
  private val monitor = new Object
  private var readerCount = 0
  private var writerActive = false
  private var waitingWriters = 0
  // Holder info for every currently-held lease, so waiting messages can name
  // a real blocker even when the most-recent acquirer has already released
  // (e.g. a launcher that took read then dropped it while another reader is
  // still holding). Keyed by lease identity (LinkedHashMap preserves
  // insertion order so we report the oldest active holder, which usually
  // reads best).
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
    val leaseOpt = monitor.synchronized {
      val available = if (isWrite) canAcquireWrite else canAcquireRead
      if (available) {
        if (isWrite) writerActive = true else readerCount += 1
        val lease = newLease(initiallyWrite = isWrite)
        holders.put(lease, acquirer)
        Some(lease)
      } else if (noWait) {
        // --no-wait failures surface up to the user, so include the contested
        // resource label in addition to the blocking launcher's identity.
        throw new Exception(
          s"${waitingMessage(currentBlocker())} (resource: '$label') and --no-wait was set, failing"
        )
      } else {
        if (isWrite) waitingWriters += 1
        None
      }
    }

    leaseOpt.getOrElse {
      val blocker = monitor.synchronized(currentBlocker())
      // Resource label is appended at the end of the line so substring
      // matchers like `ConcurrencyTests.blockedBy`, which look for the
      // command-and-PID prefix, still match while resource-aware checks
      // (e.g. `BspServerTests.sharedOutDirAllowsConcurrentCliAndBspWork`,
      // which classifies waits as benign by inspecting the resource path)
      // can still find the contested resource on the same line.
      waitingErr.println(
        s"${waitingMessage(blocker)}, waiting for it to be done... " +
          s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress) " +
          s"(resource: '$label')"
      )

      // Phase 2: wait until acquirable, then reserve.
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
