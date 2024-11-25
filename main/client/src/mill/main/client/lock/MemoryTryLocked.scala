package mill.main.client.lock

import java.util.concurrent.locks.Lock

class MemoryTryLocked(lock: Lock) extends MemoryLocked(lock) with TryLocked {

  def isLocked: Boolean = lock != null

  @throws[Exception]
  override def release(): Unit = {
    if (isLocked) super.release()
  }
}
