package mill.client.lock;

import mill.constants.DaemonFiles;

public final class Locks implements AutoCloseable {

  public final Lock launcherLock;
  public final Lock daemonLock;

  public Locks(Lock launcherLock, Lock daemonLock) {
    this.launcherLock = launcherLock;
    this.daemonLock = daemonLock;
  }

  public static Locks files(String daemonDir) throws Exception {
    return new Locks(
        new FileLock(daemonDir + "/" + DaemonFiles.launcherLock),
        new FileLock(daemonDir + "/" + DaemonFiles.daemonLock));
  }

  public static Locks memory() {
    return new Locks(new MemoryLock(), new MemoryLock());
  }

  @Override
  public void close() throws Exception {
    launcherLock.delete();
    daemonLock.delete();
  }
}
