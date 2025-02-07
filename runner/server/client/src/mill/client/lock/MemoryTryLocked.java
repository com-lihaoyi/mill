package mill.client.lock;

class MemoryTryLocked extends MemoryLocked implements TryLocked {
  public MemoryTryLocked(java.util.concurrent.locks.Lock lock) {
    super(lock);
  }

  @Override
  public boolean isLocked() {
    return lock != null;
  }

  @Override
  public void release() throws Exception {
    if (isLocked()) super.release();
  }
}
