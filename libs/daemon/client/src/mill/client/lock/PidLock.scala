package mill.client.lock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * A lock implementation that uses atomic file creation and PID + timestamp checking.
 * This works on filesystems that don't support file locking (e.g. Docker containers on macOS),
 * but at the cost of potential race conditions.
 *
 * The lock file contains "pid:startTime" which uniquely identifies a process even if
 * PIDs are reused after a process dies.
 */
class PidLock(path: String) extends Lock {
  private val lockPath: Path = Path.of(path)
  private val pid: Long = ProcessHandle.current().pid()

  private def createLockContent(): String = s"$pid:${PidLock.PROCESS_START_TIME}"

  override def toString: String =
    s"PidLock{@${Integer.toHexString(hashCode)}, path='$lockPath'}"

  override def lock(): Locked = {
    while (true) {
      val result = tryLock()
      if (result.isLocked) return result
      else Thread.sleep(1)
    }
    throw new IllegalStateException("unreachable")
  }

  override def tryLock(): TryLocked = {
    if (!isLockValid) {
      tryDeleteLockFile()
      try {
        Files.write(
          lockPath,
          createLockContent().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        new PidTryLocked(lockPath, locked = true)
      } catch {
        case _: java.nio.file.FileAlreadyExistsException =>
          // Another process grabbed it - that's fine
          new PidTryLocked(null, locked = false)
      }
    } else {
      // Lock is held by a living process
      new PidTryLocked(null, locked = false)
    }
  }

  override def probe(): Boolean = !isLockValid

  override def close(): Unit = tryDeleteLockFile()

  override def delete(): Unit = ()

  private def isLockValid: Boolean = {
    readLockInfo() match {
      case None => false // Couldn't read lock info, treat as stale
      case Some(info) =>
        // Check if process is alive and started at the recorded time
        ProcessHandle.of(info.pid)
          .filter(_.isAlive)
          .flatMap(_.info().startInstant())
          .map(_.toEpochMilli == info.timestamp)
          .orElse(false) // Process not found or no start time available = stale
    }
  }

  private def readLockInfo(): Option[PidLock.LockInfo] = {
    try {
      val content = Files.readString(lockPath, StandardCharsets.UTF_8).trim
      val parts = content.split(":")
      if (parts.length < 2) None
      else {
        try {
          val lockPid = parts(0).toLong
          val timestamp = parts(1).toLong
          Some(PidLock.LockInfo(lockPid, timestamp))
        } catch {
          case _: NumberFormatException => None
        }
      }
    } catch {
      case _: java.io.IOException => None
    }
  }

  private def tryDeleteLockFile(): Unit = {
    try Files.deleteIfExists(lockPath)
    catch {
      case _: java.io.IOException => // Ignore - another process might have deleted it
    }
  }
}

private[lock] object PidLock {
  private val PROCESS_START_TIME: Long =
    ProcessHandle.current().info().startInstant().get().toEpochMilli

  private case class LockInfo(pid: Long, timestamp: Long)
}

private[lock] class PidTryLocked(lockPath: Path, locked: Boolean) extends TryLocked {
  override def isLocked: Boolean = locked
  override def release(): Unit = {
    if (locked && lockPath != null) Files.deleteIfExists(lockPath)
  }
}
