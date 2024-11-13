package mill.main.client.lock;

import java.util.concurrent.locks.ReentrantLock;

class MemoryLock extends Lock {

  private final ReentrantLock innerLock = new ReentrantLock();

  public boolean probe() {
    return !innerLock.isLocked();
  }

  public Locked lock() {
    innerLock.lock();
    return new MemoryLocked(innerLock);
  }

  public MemoryTryLocked tryLock() {
    if (innerLock.tryLock()) return new MemoryTryLocked(innerLock);
    else return new MemoryTryLocked(null);
  }

  @Override
  public void close() throws Exception {
    innerLock.unlock();
  }
}
