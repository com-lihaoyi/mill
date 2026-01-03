package mill.client.lock;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

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
   * (e.g., some network filesystems, Docker containers on macOS).
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

  /**
   * Creates locks using the best available mechanism for the given directory.
   * Tests if the filesystem supports file locking, and uses FileLock if it does,
   * otherwise falls back to PidLock.
   *
   * @param daemonDir the directory where lock files will be created
   * @return Locks using the appropriate locking mechanism
   */
  public static Locks forDirectory(String daemonDir) throws Exception {
    if (supportsFileLocking(daemonDir)) {
      return files(daemonDir);
    } else {
      return pid(daemonDir);
    }
  }

  /**
   * Tests if the given directory's filesystem supports file locking.
   * Creates a temporary test file and attempts to acquire a lock on it.
   *
   * @param daemonDir the directory to test
   * @return true if file locking is supported, false otherwise
   */
  public static boolean supportsFileLocking(String daemonDir) {
    Path testFile = Path.of(daemonDir, ".lock-test");
    try {
      Files.createDirectories(Path.of(daemonDir));
      Files.deleteIfExists(testFile);
      try (RandomAccessFile raf = new RandomAccessFile(testFile.toFile(), "rw");
           FileChannel channel = raf.getChannel()) {
        java.nio.channels.FileLock lock = channel.tryLock();
        if (lock != null) {
          lock.release();
          return true;
        }
        return false;
      }
    } catch (Exception e) {
      return false;
    } finally {
      try {
        Files.deleteIfExists(testFile);
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public void close() throws Exception {
    launcherLock.delete();
    daemonLock.delete();
  }
}
