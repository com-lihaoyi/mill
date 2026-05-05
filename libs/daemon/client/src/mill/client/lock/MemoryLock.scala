package mill.client.lock

import java.util.concurrent.locks.ReentrantLock

class MemoryLock extends Lock {
  private val innerLock = new ReentrantLock()

  override def probe(): Boolean = !innerLock.isLocked

  override def lock(): Locked = {
    innerLock.lock()
    new MemoryLocked(innerLock)
  }

  override def tryLock(): TryLocked = {
    if (innerLock.tryLock()) new MemoryTryLocked(innerLock)
    else new MemoryTryLocked(null)
  }

  override def close(): Unit = {
    try innerLock.unlock()
    catch {
      case _: Exception =>
      // If the lock is not currently locked, close() does nothing
    }
  }
}

private[lock] class MemoryLocked(lock: java.util.concurrent.locks.Lock) extends Locked {
  override def release(): Unit = lock.unlock()
}

private[lock] class MemoryTryLocked(lock: java.util.concurrent.locks.Lock)
    extends MemoryLocked(lock) with TryLocked {
  override def isLocked: Boolean = lock != null
  override def release(): Unit = if (isLocked) super.release()
}
