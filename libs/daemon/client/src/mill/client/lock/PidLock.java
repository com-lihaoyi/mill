package mill.client.lock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A lock implementation that uses atomic file creation and PID + timestamp checking.
 * This works on filesystems that don't support file locking (e.g. Docker containers on macOS),
 * but at the cost of potential race conditions
 */
public class PidLock extends Lock {

  /**
   * Random token unique to this process, used to detect same-process lock ownership.
   * Generated once per JVM to ensure all PidLock instances in this process
   * use the same token.
   */
  private static final long PROCESS_TOKEN = ThreadLocalRandom.current().nextLong();

  private static final long PROCESS_START_TIME =
      ProcessHandle.current().info().startInstant().get().toEpochMilli();

  private final Path lockPath;
  private final long pid;

  public PidLock(String path) {
    this.lockPath = Path.of(path);
    this.pid = ProcessHandle.current().pid();
  }

  private String createLockContent() {
    return pid + ":" + PROCESS_TOKEN + ":" + PROCESS_START_TIME;
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
      if (result.isLocked()) return result;
      else Thread.sleep(1);
    }
  }

  /**
   * Attempts to acquire the lock without blocking.
   * If the lock file exists but the holder is dead (stale lock), cleans it up and retries.
   *
   * @return a TryLocked where isLocked() returns true if the lock was acquired
   */
  @Override
  public TryLocked tryLock() throws Exception {
    if (!isLockValid()) {
      tryDeleteLockFile();
      try {
        Files.write(
            lockPath,
            createLockContent().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        return new PidTryLocked(lockPath, true);
      } catch (java.nio.file.FileAlreadyExistsException e2) {
        // Another process grabbed it - that's fine
        return new PidTryLocked(null, false);
      }
    }
    // Lock is held by a living process
    return new PidTryLocked(null, false);
  }

  /**
   * Returns true if the lock is available for taking (i.e., NOT held by another process).
   */
  @Override
  public boolean probe() throws Exception {
    return !isLockValid();
  }

  @Override
  public void close() throws Exception {
    tryDeleteLockFile();
  }

  @Override
  public void delete() throws Exception {}

  /**
   * Checks if the current lock file represents a valid (non-stale) lock.
   */
  private boolean isLockValid() {
    LockInfo info = readLockInfo();
    if (info == null) return false; // Couldn't read lock info, treat as stale

    // Check if process is alive and started before the lock was created If it started
    // after, the old process is dead, PID was reused and this is a stale lock.
    return ProcessHandle.of(info.pid)
        .filter(ProcessHandle::isAlive)
        .flatMap(ph -> ph.info().startInstant())
        .map(startTime -> startTime.toEpochMilli() == info.timestamp)
        .orElse(false); // Process not found or no start time available = stale
  }

  /**
   * Reads and parses the lock file content.
   * Supports both old format (pid:token) and new format (pid:token:timestamp).
   *
   * @return LockInfo containing PID, token, and timestamp, or null if parsing failed
   */
  private LockInfo readLockInfo() {
    String content;
    try {
      content = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return null;
    }

    String[] parts = content.split(":");
    if (parts.length < 2) return null;

    try {
      long lockPid = Long.parseLong(parts[0]);
      long token = Long.parseLong(parts[1]);
      // Support old format without timestamp (treat as epoch 0, so any live process is valid)
      long timestamp = parts.length >= 3 ? Long.parseLong(parts[2]) : 0L;
      return new LockInfo(lockPid, token, timestamp);
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
    final long timestamp;

    LockInfo(long pid, long token, long timestamp) {
      this.pid = pid;
      this.token = token;
      this.timestamp = timestamp;
    }
  }
}
