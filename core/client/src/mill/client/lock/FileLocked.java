package mill.client.lock;

class FileLocked implements Locked {

  protected final java.nio.channels.FileLock lock;

  public FileLocked(java.nio.channels.FileLock lock) {
    this.lock = lock;
  }

  @Override
  public void release() throws Exception {
    this.lock.release();
  }
}
