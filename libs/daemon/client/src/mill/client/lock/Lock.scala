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
  def await(): Unit = lock().release()

  /** Returns `true` if the lock is available for taking. */
  def probe(): Boolean

  def delete(): Unit = ()
}

object Lock {
  def file(path: String): Lock = FileLock(path)
  def memory(): Lock = MemoryLock()
  def forDirectory(daemonDir: String, useFileLocks: Boolean): Lock =
    if (useFileLocks) FileLock(daemonDir)
    else PidLock(daemonDir)
}
