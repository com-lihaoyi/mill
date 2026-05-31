package mill.client.lock

/**
 * A lock implementation that uses atomic file creation and PID + timestamp checking.
 * This works on filesystems that don't support file locking (e.g. Docker containers on macOS),
 * but at the cost of potential race conditions.
 *
 * The lock file contains "pid:startTime" which uniquely identifies a process even if
 * PIDs are reused after a process dies.
 */
class PidLock(path: String) extends Lock {
  private val lockPathNio: java.nio.file.Path =
    java.nio.file.Paths.get(path).toAbsolutePath.normalize()
  private val lockPath: os.Path = os.Path(lockPathNio)
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
    throw IllegalStateException("unreachable")
  }

  override def tryLock(): TryLocked = {
    val content = createLockContent()
    if (!isLockValid) {
      // No live holder (dead/absent, or an empty/unparseable file): clear whatever is there and
      // claim the lock with an atomic exclusive create. `CREATE_NEW` (`O_CREAT|O_EXCL`) is a
      // kernel-atomic exclusive create, so only one of several racing acquirers can win.
      forceDeleteLockFile()
      try {
        val parent = lockPathNio.getParent
        if (parent != null) java.nio.file.Files.createDirectories(parent)
        java.nio.file.Files.write(
          lockPathNio,
          content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE
        )
        PidTryLocked(Some(lockPath), content, locked = true)
      } catch {
        case _: java.nio.file.FileAlreadyExistsException =>
          // Another process created it between our check and create - that's fine
          PidTryLocked(None, content, locked = false)
      }
    } else {
      // Lock is held by a living process
      PidTryLocked(None, content, locked = false)
    }
  }

  override def probe(): Boolean = !isLockValid

  override def close(): Unit = releaseOwnLockFile()

  override def delete(): Unit = ()

  private def isLockValid: Boolean = {
    readLockInfo() match {
      case None => false // No valid lock present
      case Some(info) => isHeldByLiveProcess(info)
    }
  }

  /**
   * Whether the process recorded in `info` is currently alive and holding the lock.
   *
   * Distinguishes "dead" from "start-time-unavailable": for a process owned by
   * another user or running in a restricted container, `startInstant()` can be
   * empty even though the process is alive. In that case we fall back to
   * PID-only liveness rather than mis-classifying a live holder as stale (which
   * is exactly PidLock's Docker-on-mac / cross-user target).
   */
  private def isHeldByLiveProcess(info: PidLock.LockInfo): Boolean = {
    ProcessHandle.of(info.pid).filter(_.isAlive) match {
      case h if h.isPresent =>
        h.get().info().startInstant() match {
          case start if start.isPresent => start.get().toEpochMilli == info.timestamp
          case _ => true // alive but start time unavailable: PID-only fallback
        }
      case _ => false // process genuinely absent / not alive = stale
    }
  }

  private def readLockInfo(): Option[PidLock.LockInfo] = {
    try {
      if (!os.exists(lockPath)) return None
      val content = os.read(lockPath).trim
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

  /**
   * Unconditionally delete the lock file. Used only to clean up a holder that
   * has been POSITIVELY confirmed dead; must not be used for release/close.
   */
  private def forceDeleteLockFile(): Unit = {
    try os.remove(lockPath, checkExists = false)
    catch {
      case _: java.io.IOException => // Ignore - another process might have deleted it
    }
  }

  /**
   * Release our own lock by deleting the file only if it still records THIS
   * process's content. If another process has since taken over, this is a
   * no-op so we never delete a different live process's lock.
   */
  private def releaseOwnLockFile(): Unit =
    PidLock.deleteIfOwned(lockPath, createLockContent())
}

private[lock] object PidLock {
  private val PROCESS_START_TIME: Long =
    ProcessHandle.current().info().startInstant().get().toEpochMilli

  private case class LockInfo(pid: Long, timestamp: Long)

  /**
   * Delete the lock file only if its trimmed contents still equal `ownContent`,
   * i.e. this process is still the recorded holder. A no-op if another process
   * has taken over the lock in the meantime.
   */
  private[lock] def deleteIfOwned(lockPath: os.Path, ownContent: String): Unit = {
    try {
      if (os.exists(lockPath) && os.read(lockPath).trim == ownContent) {
        os.remove(lockPath, checkExists = false)
      }
    } catch {
      case _: java.io.IOException => // Ignore - another process might have deleted it
    }
  }
}

private[lock] class PidTryLocked(lockPath: Option[os.Path], ownContent: String, locked: Boolean)
    extends TryLocked {
  override def isLocked: Boolean = locked
  override def release(): Unit = {
    // Only remove the file if it still records THIS process's content, so a
    // mis-classified takeover can never delete a different live process's lock.
    if (locked) lockPath.foreach(p => PidLock.deleteIfOwned(p, ownContent))
  }
}
