package mill.client.lock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A lock implementation that uses atomic file creation and PID + timestamp checking.
 * This works on filesystems that don't support file locking (e.g., some
 * network filesystems, Docker containers on macOS).
 *
 * <p>The lock file contains: {@code pid:token:timestamp}
 * <ul>
 *   <li>pid - the process ID that holds the lock</li>
 *   <li>token - a random token unique to the JVM (for detecting same-process lock ownership)</li>
 *   <li>timestamp - epoch millis when the lock was created (for detecting PID reuse)</li>
 * </ul>
 *
 * <p>To detect PID reuse (where a new process gets the same PID as a dead one),
 * we compare the lock creation timestamp against the process's start time.
 * If the process started after the lock was created, the PID was reused and the lock is stale.
 */
public class PidLock extends Lock {

  /**
   * Random token unique to this process, used to detect same-process lock ownership.
   * Generated once per JVM to ensure all PidLock instances in this process
   * use the same token.
   */
  private static final long PROCESS_TOKEN = ThreadLocalRandom.current().nextLong();

  private final Path lockPath;
  private final long pid;

  public PidLock(String path) {
    this.lockPath = Path.of(path);
    this.pid = ProcessHandle.current().pid();
  }

  private String createLockContent() {
    return pid + ":" + PROCESS_TOKEN + ":" + System.currentTimeMillis();
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
    // Try to create the lock file atomically
    try {
      Files.write(
          lockPath,
          createLockContent().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return new PidTryLocked(lockPath, true);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      // Lock file exists - check if it's stale
      if (!isLockValid()) {
        // Stale lock - clean it up and retry once
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
    // Nothing to close for PidLock itself
  }

  @Override
  public void delete() throws Exception {}

  /**
   * Checks if the current lock file represents a valid (non-stale) lock.
   * A lock is valid if:
   * <ul>
   *   <li>For our own process: the token matches our PROCESS_TOKEN</li>
   *   <li>For other processes: the PID is alive AND the process started before
   *       the lock was created (to detect PID reuse)</li>
   * </ul>
   *
   * @return true if the lock is valid (held by a living process), false if stale
   */
  private boolean isLockValid() {
    LockInfo info = readLockInfo();
    if (info == null) return false; // Couldn't read lock info, treat as stale

    // If it's our own lock, verify the token matches
    if (info.pid == pid) return info.token == PROCESS_TOKEN;

    // For other processes, check if they're alive and started before the lock was created
    // If it started after, the old process is dead, PID was reused and this is a stale lock.
    return ProcessHandle.of(info.pid)
        .filter(ProcessHandle::isAlive)
        .flatMap(ph -> ph.info().startInstant())
        .map(startTime -> !startTime.isAfter(Instant.ofEpochMilli(info.timestamp)))
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
