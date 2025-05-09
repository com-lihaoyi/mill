package mill.client.lock;

import mill.constants.ServerFiles;

public final class Locks implements AutoCloseable {

  public final Lock clientLock;
  public final Lock serverLock;

  public Locks(Lock clientLock, Lock serverLock) {
    this.clientLock = clientLock;
    this.serverLock = serverLock;
  }

  public static Locks files(String serverDir) throws Exception {
    return new Locks(
        new FileLock(serverDir + "/" + ServerFiles.clientLock),
        new FileLock(serverDir + "/" + ServerFiles.serverLock));
  }

  public static Locks memory() {
    return new Locks(new MemoryLock(), new MemoryLock());
  }

  @Override
  public void close() throws Exception {
    clientLock.delete();
    serverLock.delete();
  }
}
