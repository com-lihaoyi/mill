package mill.client.lock;

public abstract class Lock implements AutoCloseable {

  public abstract Locked lock() throws Exception;

  public abstract TryLocked tryLock() throws Exception;

  public void await() throws Exception {
    lock().release();
  }

  /**
   * Returns `true` if the lock is *available for taking*
   */
  public abstract boolean probe() throws Exception;

  public void delete() throws Exception {}

  public static Lock file(String path) throws Exception {
    return new FileLock(path);
  }

  public static Lock memory() {
    return new MemoryLock();
  }
}
