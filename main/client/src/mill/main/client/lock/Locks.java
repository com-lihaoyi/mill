package mill.main.client.lock;

import mill.main.client.ServerFiles;

/**
 * The locks used to manage the relationship of Mill between Mill's clients and servers.
 * The protocol is as follows:
 *
 * - Client:
 *   - Take clientLock
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take processLock. If already taken, it means another server was running
 *     (e.g. spawned by a different client) so exit immediately
 * - Server: loop:
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If clientLock is released during execution, terminate server
 *   - Send `ProxyStream.END` packet and call `clientSocket.close()`
 * - Client:
 *   - Wait for `ProxyStream.END` packet or `clientSocket.close()`,
 *     indicating server has finished execution and all data has been received
 */
final public class Locks implements AutoCloseable {

    final public Lock processLock;
    final public Lock clientLock;

    public Locks(Lock processLock, Lock clientLock){
        this.processLock = processLock;
        this.clientLock = clientLock;
    }

    public static Locks files(String lockBase) throws Exception {
        return new Locks(
            new FileLock(lockBase + "/" + ServerFiles.processLock),
            new FileLock(lockBase + "/" + ServerFiles.clientLock)
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
        processLock.close();
        clientLock.close();
    }
}
