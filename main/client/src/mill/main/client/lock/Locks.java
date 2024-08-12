package mill.main.client.lock;

import mill.main.client.ServerFiles;

final public class Locks implements AutoCloseable {

    final public Lock processLock;
    final public Lock serverLock;
    final public Lock clientLock;

    public Locks(Lock processLock, Lock serverLock, Lock clientLock){
        this.processLock = processLock;
        this.serverLock = serverLock;
        this.clientLock = clientLock;
    }

    public static Locks files(String lockBase) throws Exception {
        return new Locks(
            new FileLock(lockBase + "/" + ServerFiles.processLock),
            new FileLock(lockBase + "/" + ServerFiles.serverLock),
            new FileLock(lockBase + "/" + ServerFiles.clientLock)
        );
    }

    public static Locks memory() {
        return new Locks(
            new MemoryLock(),
            new MemoryLock(),
            new MemoryLock()
        );
    }

    @Override
    public void close() throws Exception {
        processLock.close();
        serverLock.close();
        clientLock.close();
    }
}
