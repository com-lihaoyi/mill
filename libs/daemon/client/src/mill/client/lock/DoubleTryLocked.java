package mill.client.lock;

class DoubleTryLocked extends DoubleLocked implements TryLocked {

  public DoubleTryLocked(TryLocked lock1, TryLocked lock2) {
    super(lock1, lock2);
  }

  @Override
  public boolean isLocked() {
    return lock1 != null && lock2 != null;
  }

  @Override
  public void release() throws Exception {
    if (isLocked()) super.release();
  }
}
