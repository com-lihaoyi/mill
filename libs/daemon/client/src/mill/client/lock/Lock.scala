package mill.client.lock

trait Locked extends AutoCloseable {
  def release(): Unit

  override def close(): Unit = release()
}

trait TryLocked extends Locked {
  def isLocked: Boolean
}

abstract class Lock extends AutoCloseable {
  def lock(): Locked
  def tryLock(): TryLocked

  def tryLockOption(): Option[TryLocked] = {
    val tl = tryLock()
    if (tl.isLocked) Some(tl) else None
  }

  /** Returns `true` if the lock is available for taking. */
  def probe(): Boolean =
    tryLockOption() match {
      case Some(tl) =>
        tl.release()
        true
      case None => false
    }

  def delete(): Unit = ()
}

object Lock {
  def file(path: String): Lock = FileLock(path)
  def memory(): Lock = MemoryLock()
  def forDirectory(daemonDir: String, useFileLocks: Boolean): Lock =
    if (useFileLocks) FileLock(daemonDir)
    else PidLock(daemonDir)
}
