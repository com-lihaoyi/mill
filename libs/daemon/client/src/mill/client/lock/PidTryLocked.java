package mill.client.lock;

import java.nio.file.Files;
import java.nio.file.Path;

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
    if (locked && lockPath != null) Files.deleteIfExists(lockPath);
  }
}
