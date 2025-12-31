package mill.client.lock;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents the result of a tryLock() attempt on a PidLock.
 * If isLocked() returns true, the lock was successfully acquired
 * and release() must be called to release it.
 */
class PidTryLocked implements TryLocked {

  private final Path lockPath;
  private final boolean locked;

  public PidTryLocked(Path lockPath, boolean locked) {
    this.lockPath = lockPath;
    this.locked = locked;
  }

  @Override
  public boolean isLocked() {
    return locked;
  }

  @Override
  public void release() throws Exception {
    if (locked && lockPath != null) {
      Files.deleteIfExists(lockPath);
    }
  }
}
