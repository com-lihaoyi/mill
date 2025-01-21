package mill.main.client.lock;

class FileTryLocked extends FileLocked implements TryLocked {
  public FileTryLocked(java.nio.channels.FileLock lock) {
    super(lock);
  }

  public boolean isLocked() {
    return lock != null;
  }

  public void release() throws Exception {
    if (isLocked()) super.release();
  }
}
