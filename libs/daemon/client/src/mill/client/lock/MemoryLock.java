package mill.client.lock;

import java.util.concurrent.locks.ReentrantLock;

public class MemoryLock extends Lock {

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
    MemoryTryLocked res =
        innerLock.tryLock() ? new MemoryTryLocked(innerLock) : new MemoryTryLocked(null);

    return res;
  }

  @Override
  public void close() throws Exception {
    try {
      innerLock.unlock();
    } catch (Exception e) {
      // If the lock is not currently locked, close() does nothing, but shouldn't throw
    }
  }
}
