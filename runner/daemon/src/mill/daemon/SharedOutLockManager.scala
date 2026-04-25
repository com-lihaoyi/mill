package mill.daemon

import mill.api.daemon.internal.NonFatal
import mill.client.lock.{Lock, Locked}
import mill.constants.DaemonFiles

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refcounted handle to the cross-process out/ folder file lock.
 *
 * Multiple concurrent launcher leases inside a single daemon share one
 * file-lock acquisition: the lock is taken when the first launcher's lease
 * is requested and released only when the last lease is closed. This means
 * the daemon does not hold the file lock when idle (no active launchers,
 * no in-flight evaluation) — a no-daemon process or another daemon can take
 * it then. When a launcher arrives, [[lease]] acquires the file lock if
 * needed (blocking with a "waiting for it to be done..." message if another
 * process holds it) and returns a lease that the caller releases at the
 * end of evaluation.
 *
 * For no-daemon mode there is at most one outstanding lease; the same code
 * path still works (count goes 0→1 then 1→0 around each evaluation).
 *
 * To make the holder of the lock identifiable to external waiters, the
 * first acquiring launcher writes its command + PID to a workspace-level
 * holder file (`out/mill-out-lock-holder.json`). Subsequent intra-daemon
 * peers leave this file alone (the original acquirer is the one that's
 * "in the way" until its work is done plus all peers it gathered). The
 * file is removed when the last lease is released.
 */
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

  /**
   * Acquire a lease against the shared file lock. If the lock is already
   * held by this manager (because a peer launcher in the same daemon holds
   * one), the new lease shares it. If the lock is held by an external
   * process (another daemon or a no-daemon), this blocks (after printing a
   * waiting message via `waitingErr`) until the external holder releases.
   *
   * Returns `None` when `noBuildLock` is set, so callers can use a plain
   * `Option[Locked]` regardless of mode.
   */
  def lease(
      activeCommandMessage: String,
      launcherPid: Long,
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      waitingErr: PrintStream
  ): Option[Locked] = {
    if (noBuildLock) return None

    val mustAcquire = monitor.synchronized {
      if (closed) throw new IllegalStateException("SharedOutLockManager is closed")
      count += 1
      // If a peer is currently in the middle of acquiring the file lock,
      // wait for it to finish. Either it'll succeed (and we share its lease)
      // or fail (and we need to retry ourselves).
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
            // Roll back the count + acquiring flag; let the next waiter try.
            monitor.synchronized {
              count -= 1
              acquiring = false
              monitor.notifyAll()
            }
            throw t
        }

      writeHolderFile(out, activeCommandMessage, launcherPid)

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
          removeHolderFile(out)
          try toRelease.release()
          catch { case _: Throwable => () }
        }
      }
  }

  override def close(): Unit = monitor.synchronized {
    closed = true
    if (heldLease != null) {
      removeHolderFile(out)
      try heldLease.release()
      catch { case _: Throwable => () }
      heldLease = null
    }
  }
}

private[mill] object SharedOutLockManager {

  private val holderFileName = "mill-out-lock-holder.json"

  /**
   * Compose the prefix string used in waiting / no-wait messages when the
   * cross-process file lock is held by an external Mill process. Reads the
   * workspace-level holder file (written by whichever process currently
   * holds the file lock) to identify it.
   */
  def activeOtherProcessPrefix(out: os.Path): String = {
    val (command, pidOpt) = readHolderFile(out)
    val cmdSuffix = if (command.isEmpty) "" else s" running '$command',"
    s"Another Mill process with PID ${pidOpt.fold("<unknown>")(_.toString)} is" +
      (if (cmdSuffix.isEmpty) " using out/," else cmdSuffix)
  }

  private def writeHolderFile(out: os.Path, command: String, pid: Long): Unit = {
    try mill.api.BuildCtx.withFilesystemCheckerDisabled {
        val cmdJson = ujson.write(ujson.Str(command))
        val json = s"""{"pid":$pid,"command":$cmdJson}"""
        os.makeDir.all(out)
        os.write.over(out / holderFileName, json)
      }
    catch { case _: Throwable => () }
  }

  private def removeHolderFile(out: os.Path): Unit = {
    try mill.api.BuildCtx.withFilesystemCheckerDisabled(
        os.remove(out / holderFileName, checkExists = false)
      )
    catch { case _: Throwable => () }
  }

  private def readHolderFile(out: os.Path): (String, Option[Long]) = {
    val file = out / holderFileName
    if (!os.exists(file)) ("", None)
    else
      try {
        val json = ujson.read(os.read(file)).obj
        val command = json.get("command").map(_.str).getOrElse("")
        val pid = json.get("pid").map(_.num.toLong)
        (command, pid)
      } catch { case NonFatal(_) => ("", None) }
  }
}
