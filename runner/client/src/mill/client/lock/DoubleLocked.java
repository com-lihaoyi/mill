package mill.client.lock;

class DoubleLocked implements Locked {

  protected final Locked lock1;
  protected final Locked lock2;

  public DoubleLocked(Locked lock1, Locked lock2) {
    this.lock1 = lock1;
    this.lock2 = lock2;
  }

  @Override
  public void release() throws Exception {
    this.lock2.release();
    this.lock1.release();
  }
}
