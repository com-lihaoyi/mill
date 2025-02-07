package mill.main.client.lock

import java.util.concurrent.locks.Lock

class MemoryLocked(protected val lock: Lock) extends Locked {

  @throws[Exception]
  override def release(): Unit = {
    lock.unlock()
  }
}
