package mill.client.lock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A lock implementation that uses atomic file creation and PID + timestamp checking.
 * This works on filesystems that don't support file locking (e.g. Docker containers on macOS),
 * but at the cost of potential race conditions.
 *
 * The lock file contains "pid:startTime" which uniquely identifies a process even if
 * PIDs are reused after a process dies.
 */
public class PidLock extends Lock {

  private static final long PROCESS_START_TIME =
      ProcessHandle.current().info().startInstant().get().toEpochMilli();

  private final Path lockPath;
  private final long pid;

  public PidLock(String path) {
    this.lockPath = Path.of(path);
    this.pid = ProcessHandle.current().pid();
  }

  private String createLockContent() {
    return pid + ":" + PROCESS_START_TIME;
  }

  @Override
  public String toString() {
    return "PidLock{@" + Integer.toHexString(hashCode()) + ", path='" + lockPath + "'}";
  }

  @Override
  public Locked lock() throws Exception {
    while (true) {
      TryLocked result = tryLock();
      if (result.isLocked()) return result;
      else Thread.sleep(1);
    }
  }

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
      long timestamp = Long.parseLong(parts[1]);
      return new LockInfo(lockPid, timestamp);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void tryDeleteLockFile() {
    try {
      Files.deleteIfExists(lockPath);
    } catch (IOException e) {
      // Ignore - another process might have deleted it
    }
  }

  private static class LockInfo {
    final long pid;
    final long timestamp;

    LockInfo(long pid, long timestamp) {
      this.pid = pid;
      this.timestamp = timestamp;
    }
  }
}
