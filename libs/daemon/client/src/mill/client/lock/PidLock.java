package mill.client.lock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A lock implementation that uses atomic file creation and PID + random token checking.
 * This works on filesystems that don't support file locking (e.g., some
 * network filesystems, Docker containers on macOS).
 *
 * <p>The lock file contains the PID and a random token unique to this process,
 * in the format "{pid}:{randomToken}". Using both PID and a random token
 * prevents issues with PID reuse - if a process dies and a new unrelated process
 * gets the same PID, we can detect this because the token will differ.
 *
 * <p>Stale locks (where the process has died or the PID was reused) are
 * automatically detected and cleaned up.
 */
public class PidLock extends Lock {

  /**
   * Random token unique to this process, used to detect PID reuse.
   * Generated once per JVM to ensure all PidLock instances in this process
   * use the same token.
   */
  private static final long PROCESS_TOKEN = ThreadLocalRandom.current().nextLong();

  private final Path lockPath;
  private final long pid;
  private final String lockContent;

  public PidLock(String path) {
    this.lockPath = Path.of(path);
    this.pid = ProcessHandle.current().pid();
    this.lockContent = pid + ":" + PROCESS_TOKEN;
  }

  @Override
  public String toString() {
    return "PidLock{@" + Integer.toHexString(hashCode()) + ", path='" + lockPath + "'}";
  }

  /**
   * Attempts to acquire the lock, blocking until successful.
   */
  @Override
  public Locked lock() throws Exception {
    while (true) {
      TryLocked result = tryLock();
      if (result.isLocked()) {
        return result;
      }
      // Small sleep before retrying to avoid busy-spinning
      Thread.sleep(1);
    }
  }

  /**
   * Attempts to acquire the lock without blocking.
   *
   * @return a TryLocked where isLocked() returns true if the lock was acquired
   */
  @Override
  public TryLocked tryLock() throws Exception {
    // First, clean up any stale lock
    cleanupStaleLock();

    // Try to create the lock file atomically
    try {
      Files.write(
          lockPath,
          lockContent.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return new PidTryLocked(lockPath, true);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      // Lock is held by another process
      return new PidTryLocked(null, false);
    }
  }

  /**
   * Returns true if the lock is available for taking (i.e., NOT held by another process).
   */
  @Override
  public boolean probe() throws Exception {
    if (!Files.exists(lockPath)) {
      return true; // Lock file doesn't exist, lock is available
    }

    return !isLockValid();
  }

  @Override
  public void close() throws Exception {
    // Nothing to close for PidLock itself
  }

  @Override
  public void delete() throws Exception {
    try {
      // Only delete if we own the lock (our content is in the file)
      String content = readLockFileContent();
      if (lockContent.equals(content)) {
        Files.deleteIfExists(lockPath);
      }
    } catch (Exception e) {
      // Ignore errors during cleanup
    }
  }

  /**
   * Cleans up a stale lock file if the holding process is dead or PID was reused.
   */
  private void cleanupStaleLock() {
    if (!Files.exists(lockPath)) {
      return;
    }

    if (!isLockValid()) {
      tryDeleteLockFile();
    }
  }

  /**
   * Checks if the current lock file represents a valid (non-stale) lock.
   * A lock is valid if the PID exists AND is alive. We also store a random token
   * to detect PID reuse, but we can only verify our own token - for other processes,
   * we just check if the PID is alive.
   *
   * @return true if the lock is valid (held by a living process), false if stale
   */
  private boolean isLockValid() {
    LockInfo info = readLockInfo();
    if (info == null) {
      return false; // Couldn't read lock info, treat as stale
    }

    // If it's our own lock, verify the token matches
    if (info.pid == pid) {
      return info.token == PROCESS_TOKEN;
    }

    // For other processes, check if they're alive
    return ProcessHandle.of(info.pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /**
   * Reads the raw content of the lock file.
   */
  private String readLockFileContent() {
    try {
      return Files.readString(lockPath, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Reads and parses the lock file content.
   *
   * @return LockInfo containing PID and token, or null if parsing failed
   */
  private LockInfo readLockInfo() {
    String content = readLockFileContent();
    if (content == null) {
      return null;
    }

    String[] parts = content.split(":");
    if (parts.length != 2) {
      return null;
    }

    try {
      long pid = Long.parseLong(parts[0]);
      long token = Long.parseLong(parts[1]);
      return new LockInfo(pid, token);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Attempts to delete the lock file, ignoring errors.
   */
  private void tryDeleteLockFile() {
    try {
      Files.deleteIfExists(lockPath);
    } catch (IOException e) {
      // Ignore - another process might have deleted it
    }
  }

  private static class LockInfo {
    final long pid;
    final long token;

    LockInfo(long pid, long token) {
      this.pid = pid;
      this.token = token;
    }
  }
}
