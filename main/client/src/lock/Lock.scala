package mill.main.client.lock

abstract class Lock extends AutoCloseable {

  @throws[Exception]
  def lock(): Locked

  @throws[Exception]
  def tryLock(): TryLocked

  @throws[Exception]
  def await(): Unit = {
    lock().release()
  }

  /**
   * Returns `true` if the lock is *available for taking*
   */
  @throws[Exception]
  def probe(): Boolean

  @throws[Exception]
  def delete(): Unit = {}

}

object Lock {
  @throws[Exception]
  def file(path: String): Lock = new FileLock(path)

  def memory(): Lock = new MemoryLock()
}
