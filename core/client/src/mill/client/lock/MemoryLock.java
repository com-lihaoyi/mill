package mill.client.lock;

import java.util.concurrent.locks.ReentrantLock;

class MemoryLock extends Lock {

  private final ReentrantLock innerLock = new ReentrantLock();

  @Override
  public boolean probe() {
    return !innerLock.isLocked();
  }

  @Override
  public Locked lock() {
    innerLock.lock();
    return new MemoryLocked(innerLock);
  }

  @Override
  public MemoryTryLocked tryLock() {
    if (innerLock.tryLock()) return new MemoryTryLocked(innerLock);
    else return new MemoryTryLocked(null);
  }

  @Override
  public void close() throws Exception {
    innerLock.unlock();
  }
}
