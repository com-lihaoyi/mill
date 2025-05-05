package mill.client.lock;

class MemoryLocked implements Locked {

  protected final java.util.concurrent.locks.Lock lock;

  public MemoryLocked(java.util.concurrent.locks.Lock lock) {
    this.lock = lock;
  }

  @Override
  public void release() throws Exception {
    lock.unlock();
  }
}
