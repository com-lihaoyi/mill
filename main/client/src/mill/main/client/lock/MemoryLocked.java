package mill.main.client.lock;

class MemoryLocked implements Locked {

  protected final java.util.concurrent.locks.Lock lock;

  public MemoryLocked(java.util.concurrent.locks.Lock lock) {
    this.lock = lock;
  }

  public void release() throws Exception {
    lock.unlock();
  }
}
