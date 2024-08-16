package mill.main.client.lock;

import mill.main.client.ServerFiles;


final public class Locks implements AutoCloseable {

    final public Lock clientLock;
    final public Lock processLock;

    public Locks(Lock clientLock, Lock processLock){
        this.clientLock = clientLock;
        this.processLock = processLock;
    }

    public static Locks files(String serverDir) throws Exception {
        return new Locks(
            new FileLock(serverDir + "/" + ServerFiles.clientLock),
            new FileLock(serverDir + "/" + ServerFiles.processLock)
        );
    }

    public static Locks memory() {
        return new Locks(
            new MemoryLock(),
            new MemoryLock()
        );
    }

    @Override
    public void close() throws Exception {
        clientLock.delete();
        processLock.delete();
    }
}
