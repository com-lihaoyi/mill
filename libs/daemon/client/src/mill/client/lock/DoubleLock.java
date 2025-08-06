package mill.client.lock;

/** Combines two locks into one, making sure we only lock if we can get both locks. */
public class DoubleLock extends Lock {

  private final Lock lock1;
  private final Lock lock2;

  public DoubleLock(Lock lock1, Lock lock2) {
    this.lock1 = lock1;
    this.lock2 = lock2;
  }

  @Override
  public String toString() {
    return "DoubleLock{" +
      "lock1=" + lock1 +
      ", lock2=" + lock2 +
      '}';
  }

  @Override
  public Locked lock() throws Exception {
    Locked l1 = null;
    Locked l2 = null;
    Locked result = null;
    try {
      l1 = lock1.lock();
      l2 = lock2.lock();
      result = new DoubleLocked(l1, l2);
    } finally {
      if (result == null) {
        if (l2 != null) l2.release();
        if (l1 != null) l1.release();
      }
    }

    return result;
  }

  @Override
  public TryLocked tryLock() throws Exception {
    TryLocked l1 = null;
    TryLocked l2 = null;
    TryLocked result = null;
    try {
      l1 = lock1.tryLock();
      if (l1.isLocked()) {
        l2 = lock2.tryLock();
        if (l2.isLocked()) result = new DoubleTryLocked(l1, l2);
      }
    } finally {
      if (result == null) {
        if (l2 != null) l2.release();
        if (l1 != null) l1.release();
      }
    }

    if (result == null) result = new DoubleTryLocked(null, null);

    return result;
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
    // Unlock the locks in the opposite order in which we originally took them
    lock2.close();
    lock1.close();
  }

  @Override
  public void delete() throws Exception {
    close();
  }
}
