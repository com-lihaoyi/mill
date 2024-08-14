package mill.main.client.lock;

import mill.main.client.ServerFiles;

/**
 * The locks used to manage the relationship of Mill between Mill's clients and servers.
 * The protocol is as follows:
 *
 * - Client:
 *   - Take clientLock
 *   - If processLock is not taken, it means we need to spawn the server
 *      - Spawn the server and wait for processLock to be taken
 * - Server:
 *   - take processLock
 * - Server: loop:
 *   - Take serverLock,
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If clientLock is released during execution, terminate server
 *   - Release serverLock
 * - Client:
 *   - Wait for serverLock to be released, indicating server has finished execution
 *   - Give 50ms grace period for server output to arrive over pipe
 */
final public class Locks implements AutoCloseable {

    final public Lock processLock;
    final public Lock clientLock;

    public Locks(Lock processLock, Lock serverLock, Lock clientLock){
        this.processLock = processLock;
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
        clientLock.close();
    }
}
