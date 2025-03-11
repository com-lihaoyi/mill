package mill.client.lock;

class FileTryLocked extends FileLocked implements TryLocked {
  public FileTryLocked(java.nio.channels.FileLock lock) {
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
