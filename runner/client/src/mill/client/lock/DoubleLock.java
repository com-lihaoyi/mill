package mill.client.lock;

public class DoubleLock extends Lock {

  private final Lock lock1;
  private final Lock lock2;

  public DoubleLock(Lock lock1, Lock lock2) throws Exception {
    this.lock1 = lock1;
    this.lock2 = lock2;
  }

  @Override
  public Locked lock() throws Exception {
    return new DoubleLocked(lock1.lock(), lock2.lock());
  }

  @Override
  public TryLocked tryLock() throws Exception {
    TryLocked l1 = lock1.tryLock();
    TryLocked l2 = lock2.tryLock();
    if (l1.isLocked() && l2.isLocked()) {
      return new DoubleTryLocked(l1, l2);
    } else {
      l1.release();
      l2.release();
      return new DoubleTryLocked(null, null);
    }
  }

  @Override
  public boolean probe() throws Exception {
    TryLocked tl = tryLock();
    if (!tl.isLocked()) return true;
    else {
      tl.release();
      return false;
    }
  }

  @Override
  public void close() throws Exception {
    lock1.close();
    lock2.close();
  }

  @Override
  public void delete() throws Exception {
    close();
  }
}
