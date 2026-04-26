package mill.daemon

import mill.client.lock.{Lock, Locked}
import mill.constants.DaemonFiles
import mill.internal.LauncherRecordStore

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

class SharedOutLockManager(
    fileLock: Lock,
    out: os.Path
) extends AutoCloseable {
  private val monitor = new Object
  private var refCount = 0
  private var heldLease: Locked = null
  private var acquiring = false
  private var closed = false

  def lease(
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      waitingErr: PrintStream
  ): SharedOutLockManager.Lease = {
    if (noBuildLock) return SharedOutLockManager.Lease.Noop

    val mustAcquire = monitor.synchronized {
      if (closed) throw new IllegalStateException("SharedOutLockManager is closed")
      refCount += 1
      while (acquiring && !closed) monitor.wait()
      // Recheck closed: a concurrent close() can happen while we wait, in
      // which case we must not proceed to fileLock.tryLock() on a manager
      // whose held lease has already been released.
      if (closed) {
        refCount -= 1
        throw new IllegalStateException("SharedOutLockManager is closed")
      }
      if (heldLease != null) false
      else {
        acquiring = true
        true
      }
    }

    if (mustAcquire) {
      val locked =
        try {
          val tryLocked = fileLock.tryLock()
          if (tryLocked.isLocked) tryLocked
          else {
            if (noWaitForBuildLock)
              throw new Exception(
                s"${SharedOutLockManager.activeOtherProcessPrefix(out)} and --no-wait-for-build-lock was set, failing"
              )
            val consoleLogPath = out / DaemonFiles.millConsoleTail
            waitingErr.println(
              s"${SharedOutLockManager.activeOtherProcessPrefix(out)} waiting for it to be done... " +
                s"(tail -F ${consoleLogPath.relativeTo(mill.api.BuildCtx.workspaceRoot)} to see its progress)"
            )
            fileLock.lock()
          }
        } catch {
          case t: Throwable =>
            monitor.synchronized {
              refCount -= 1
              acquiring = false
              monitor.notifyAll()
            }
            throw t
        }

      monitor.synchronized {
        heldLease = locked
        acquiring = false
        monitor.notifyAll()
      }
    }

    new SharedOutLockManager.Lease {
      private val released = new AtomicBoolean(false)

      override def close(): Unit =
        if (released.compareAndSet(false, true)) {
          val toRelease = monitor.synchronized {
            refCount -= 1
            if (refCount == 0 && heldLease != null) {
              val lease = heldLease
              heldLease = null
              lease
            } else null
          }
          if (toRelease != null) {
            try toRelease.release()
            catch { case _: Throwable => () }
          }
        }
    }
  }

  override def close(): Unit = monitor.synchronized {
    closed = true
    if (heldLease != null) {
      try heldLease.release()
      catch { case _: Throwable => () }
      heldLease = null
    }
    monitor.notifyAll()
  }
}

private[mill] object SharedOutLockManager {
  sealed trait Lease extends AutoCloseable
  object Lease {
    object Noop extends Lease {
      override def close(): Unit = ()
    }
  }

  def activeOtherProcessPrefix(out: os.Path): String = {
    val recordOpt = LauncherRecordStore.mostRecentActive(out)
    val command = recordOpt.map(_.command).getOrElse("")
    val pidOpt = recordOpt.map(_.pid)
    val cmdSuffix = if (command.isEmpty) "" else s" running '$command',"
    s"Another Mill process with PID ${pidOpt.fold("<unknown>")(_.toString)} is" +
      (if (cmdSuffix.isEmpty) " using out/," else cmdSuffix)
  }
}
