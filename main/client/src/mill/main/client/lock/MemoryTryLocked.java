package mill.main.client.lock;

class MemoryTryLocked extends MemoryLocked implements TryLocked {
  public MemoryTryLocked(java.util.concurrent.locks.Lock lock) {
    super(lock);
  }

  public boolean isLocked() {
    return lock != null;
  }

  public void release() throws Exception {
    if (isLocked()) super.release();
  }
}
