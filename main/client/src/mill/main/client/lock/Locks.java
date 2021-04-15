package mill.main.client.lock;

public class Locks implements AutoCloseable {

    public Lock processLock;
    public Lock serverLock;
    public Lock clientLock;

    public static Locks files(String lockBase) throws Exception {
        return new Locks(){{
            processLock = new FileLock(lockBase + "/pid");
            serverLock = new FileLock(lockBase + "/serverLock");
            clientLock = new FileLock(lockBase + "/clientLock");
        }};
    }

    public static Locks memory() {
        return new Locks(){{
            this.processLock = new MemoryLock();
            this.serverLock = new MemoryLock();
            this.clientLock = new MemoryLock();
        }};
    }

    @Override
    public void close() throws Exception {
        processLock.close();
        serverLock.close();
        clientLock.close();
    }
}
