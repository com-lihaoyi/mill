package mill.client.lock;

import mill.constants.DaemonFiles;

public final class Locks implements AutoCloseable {

  /** The lock used by the client process (the launcher that launches the server). */
  public final Lock launcherLock;

  /** The lock used by the server process. */
  public final Lock daemonLock;

  public Locks(Lock launcherLock, Lock daemonLock) {
    this.launcherLock = launcherLock;
    this.daemonLock = daemonLock;
  }

  /**
   * @deprecated Use {@link #forDirectory(String)} instead, which automatically
   *             chooses between file-based and PID-based locking.
   */
  @Deprecated
  public static Locks files(String daemonDir) throws Exception {
    return new Locks(
        new FileLock(daemonDir + "/" + DaemonFiles.launcherLock),
        new FileLock(daemonDir + "/" + DaemonFiles.daemonLock));
  }

  /**
   * Creates locks using PID-based locking (atomic file creation + PID checking).
   * This works on all filesystems, including those that don't support file locking
   * (e.g., some network filesystems, Docker containers on macOS), at the cost
   * of some potential race conditions
   *
   * @deprecated Use {@link #forDirectory(String)} instead, which automatically
   *             chooses between file-based and PID-based locking.
   */
  @Deprecated
  public static Locks pid(String daemonDir) {
    return new Locks(
        new PidLock(daemonDir + "/" + DaemonFiles.launcherLock),
        new PidLock(daemonDir + "/" + DaemonFiles.daemonLock));
  }

  public static Locks memory() {
    return new Locks(new MemoryLock(), new MemoryLock());
  }

  public static Locks forDirectory(String daemonDir) {
    return forDirectory(daemonDir, false);
  }

  public static Locks forDirectory(String daemonDir, boolean useFileLocks) {
    if (useFileLocks) {
      try {
        return files(daemonDir);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create file locks", e);
      }
    }
    return pid(daemonDir);
  }

  @Override
  public void close() throws Exception {
    launcherLock.delete();
    daemonLock.delete();
  }
}
