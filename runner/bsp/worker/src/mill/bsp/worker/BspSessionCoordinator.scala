package mill.bsp.worker

import mill.api.Logger
import mill.api.daemon.internal.NonFatal
import mill.client.lock.{Lock, Locked}
import mill.constants.OutFiles.OutFiles

import java.util.concurrent.{ConcurrentHashMap, Semaphore}

private[worker] final class BspSessionCoordinator(
    out: os.Path,
    sessionProcessPid: Long,
    noWaitForBspLock: Boolean,
    killOther: Boolean,
    logger: Logger,
    stopOwner: () => Unit
) extends AutoCloseable {
  import BspSessionCoordinator.*

  private val ownerToken = new Object
  private var sessionLease: SessionLease = null

  def isInitialized: Boolean = synchronized(sessionLease != null)

  def initialize(lockId: String): Unit = synchronized {
    if (sessionLease == null) sessionLease = acquire(lockId)
  }

  override def close(): Unit = synchronized {
    if (sessionLease != null) {
      sessionLease.close()
      sessionLease = null
    }
  }

  private def acquire(lockId: String): SessionLease = {
    val processLock = processLockFor(lockId)
    val fileLock = Lock.file((out / OutFiles.millBspLock(lockId)).toString)
    val activeBspFile = out / OutFiles.millActiveBsp(lockId)

    def readActiveInfo(): Option[Long] =
      try {
        val json = os.read(activeBspFile)
        val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
        pidPattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)
      } catch {
        case NonFatal(_) => None
      }

    def waitForFileLock(): Locked = {
      while (true) {
        val tryLocked = fileLock.tryLock()
        if (tryLocked.isLocked) return tryLocked
        Thread.sleep(10L)
      }
      throw new IllegalStateException("unreachable")
    }

    def terminateOther(pidOpt: Option[Long]): Unit =
      pidOpt match {
        case Some(pid) if pid == sessionProcessPid =>
          logger.warn(
            s"Active BSP process PID $pid matches the current launcher; waiting for the existing session to stop"
          )
        case Some(pid) =>
          val handleOpt = ProcessHandle.of(pid)
          if (handleOpt.isPresent) {
            val handle = handleOpt.get()
            if (handle.isAlive()) {
              if (handle.destroy())
                logger.info(s"Sent SIGTERM to process $pid")
              else
                logger.warn(s"Could not send SIGTERM to process $pid")
              var i = 200
              while (i > 0 && handle.isAlive()) {
                Thread.sleep(10L)
                i -= 1
              }
              if (handle.isAlive())
                if (handle.destroyForcibly())
                  logger.info(s"Sent SIGKILL to process $pid")
                else
                  logger.warn(s"Could not send SIGKILL to process $pid")
            } else logger.info(s"Other Mill process with PID $pid exited")
          } else logger.info(s"Other Mill process with PID $pid exited")
        case None =>
          logger.warn(
            s"PID of other Mill process not found in $activeBspFile, could not terminate it"
          )
      }

    def stopOtherLocalSession(): Boolean =
      shutdownActiveSession(lockId, ownerToken)

    var processLease: ProcessLockLease = null
    var fileLease: Locked = null
    var acquiredLease: SessionLease = null
    try {
      processLease = processLock.tryLock()
      if (processLease == null) {
        if (noWaitForBspLock)
          throw new Exception("Another Mill BSP process is running, failing")

        val pidOpt = readActiveInfo()
        if (killOther) {
          if (stopOtherLocalSession())
            logger.info(s"Asked the active BSP session for '$lockId' to shut down")
          else terminateOther(pidOpt)
        } else
          logger.info(
            s"Another Mill BSP server is running with PID ${pidOpt.fold("<unknown>")(_.toString)} waiting for it to be done..."
          )

        processLease = processLock.lock()
      }

      val tryLocked = fileLock.tryLock()
      fileLease =
        if (tryLocked.isLocked) tryLocked
        else {
          if (noWaitForBspLock)
            throw new Exception("Another Mill BSP process is running, failing")

          val pidOpt = readActiveInfo()
          if (killOther) terminateOther(pidOpt)
          else
            logger.info(
              s"Another Mill BSP server is running with PID ${pidOpt.fold("<unknown>")(_.toString)} waiting for it to be done..."
            )

          waitForFileLock()
        }

      val daemonPid = ProcessHandle.current().pid()
      os.write.over(activeBspFile, s"""{"pid":$sessionProcessPid,"serverPid":$daemonPid}""")
      registerActiveSession(lockId, ActiveSession(ownerToken, sessionProcessPid, stopOwner))

      acquiredLease = new SessionLease(
        lockId = lockId,
        activeBspFile = activeBspFile,
        fileLock = fileLock,
        fileLease = fileLease,
        processLease = processLease,
        sessionProcessPid = sessionProcessPid,
        ownerToken = ownerToken
      )
      acquiredLease
    } finally {
      if (acquiredLease == null) {
        if (fileLease != null)
          try fileLease.close()
          catch { case _: Throwable => () }
        try fileLock.close()
        catch { case _: Throwable => () }
        if (processLease != null)
          try processLease.close()
          catch { case _: Throwable => () }
      }
    }
  }
}

private object BspSessionCoordinator {
  private final case class ActiveSession(
      ownerToken: AnyRef,
      processPid: Long,
      shutdown: () => Unit
  )

  private final class ProcessLock(private val semaphore: Semaphore) {
    def lock(): ProcessLockLease = {
      semaphore.acquire()
      new ProcessLockLease(semaphore)
    }
    def tryLock(): ProcessLockLease =
      if (semaphore.tryAcquire()) new ProcessLockLease(semaphore)
      else null
  }

  private final class ProcessLockLease(private val semaphore: Semaphore) extends AutoCloseable {
    private var released = false

    override def close(): Unit = synchronized {
      if (!released) {
        released = true
        semaphore.release()
      }
    }
  }

  private final class SessionLease(
      lockId: String,
      activeBspFile: os.Path,
      fileLock: Lock,
      fileLease: Locked,
      processLease: ProcessLockLease,
      sessionProcessPid: Long,
      ownerToken: AnyRef
  ) extends AutoCloseable {
    private var closed = false

    override def close(): Unit = synchronized {
      if (!closed) {
        closed = true
        unregisterActiveSession(lockId, ownerToken)
        try {
          val currentPid =
            if (os.exists(activeBspFile)) {
              val json = os.read(activeBspFile)
              val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
              pidPattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)
            } else None
          if (currentPid.contains(sessionProcessPid))
            os.remove(activeBspFile, checkExists = false)
        } catch {
          case NonFatal(_) => ()
        }
        try fileLease.close()
        catch { case _: Throwable => () }
        try fileLock.close()
        catch { case _: Throwable => () }
        try processLease.close()
        catch { case _: Throwable => () }
      }
    }
  }

  private val processLocks = new ConcurrentHashMap[String, ProcessLock]()
  private val activeSessions = new ConcurrentHashMap[String, ActiveSession]()

  private def processLockFor(lockId: String): ProcessLock =
    processLocks.computeIfAbsent(lockId, _ => new ProcessLock(new Semaphore(1, true)))

  private def registerActiveSession(lockId: String, session: ActiveSession): Unit =
    activeSessions.put(lockId, session)

  private def shutdownActiveSession(lockId: String, ownerToken: AnyRef): Boolean = {
    val session = activeSessions.get(lockId)
    if (session != null && session.ownerToken.ne(ownerToken)) {
      session.shutdown()
      true
    } else false
  }

  private def unregisterActiveSession(lockId: String, ownerToken: AnyRef): Unit = {
    val session = activeSessions.get(lockId)
    if (session != null && session.ownerToken.eq(ownerToken))
      activeSessions.remove(lockId, session)
  }
}
