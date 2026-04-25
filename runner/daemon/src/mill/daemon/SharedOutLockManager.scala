package mill.daemon

import mill.client.lock.{Lock, Locked}
import mill.constants.DaemonFiles
import mill.internal.LauncherRecordStore

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

private[mill] final class SharedOutLockManager(
    fileLock: Lock,
    out: os.Path
) extends AutoCloseable {
  import SharedOutLockManager.*

  private val monitor = new Object
  private var count = 0
  private var heldLease: Locked = null
  private var acquiring = false
  private var closed = false

  def lease(
      activeCommandMessage: String,
      launcherPid: Long,
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      waitingErr: PrintStream
  ): Option[Locked] = {
    val _ = (activeCommandMessage, launcherPid)
    if (noBuildLock) return None

    val mustAcquire = monitor.synchronized {
      if (closed) throw new IllegalStateException("SharedOutLockManager is closed")
      count += 1
      while (acquiring) monitor.wait()
      if (heldLease != null) false
      else {
        acquiring = true
        true
      }
    }

    if (mustAcquire) {
      val locked: Locked =
        try {
          val tryLocked = fileLock.tryLock()
          if (tryLocked.isLocked) tryLocked
          else {
            if (noWaitForBuildLock)
              throw new Exception(
                s"${activeOtherProcessPrefix(out)} and " +
                  "--no-wait-for-build-lock was set, failing"
              )
            val consoleLogPath = out / DaemonFiles.millConsoleTail
            waitingErr.println(
              s"${activeOtherProcessPrefix(out)} waiting for it to be done... " +
                s"(tail -F ${consoleLogPath.relativeTo(mill.api.BuildCtx.workspaceRoot)} " +
                s"to see its progress)"
            )
            fileLock.lock()
          }
        } catch {
          case t: Throwable =>
            monitor.synchronized {
              count -= 1
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

    Some(makeLease())
  }

  private def makeLease(): Locked = new Locked {
    private val released = new AtomicBoolean(false)
    override def release(): Unit =
      if (released.compareAndSet(false, true)) {
        val toRelease: Locked | Null = monitor.synchronized {
          count -= 1
          if (count == 0 && heldLease != null) {
            val l = heldLease
            heldLease = null
            l
          } else null
        }
        if (toRelease != null) {
          try toRelease.release()
          catch { case _: Throwable => () }
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
  }
}

private[mill] object SharedOutLockManager {

  def activeOtherProcessPrefix(out: os.Path): String = {
    val recordOpt = LauncherRecordStore.mostRecentActive(out)
    val command = recordOpt.map(_.command).getOrElse("")
    val pidOpt = recordOpt.map(_.pid)
    val cmdSuffix = if (command.isEmpty) "" else s" running '$command',"
    s"Another Mill process with PID ${pidOpt.fold("<unknown>")(_.toString)} is" +
      (if (cmdSuffix.isEmpty) " using out/," else cmdSuffix)
  }
}
