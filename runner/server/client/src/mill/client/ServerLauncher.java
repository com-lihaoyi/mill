package mill.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mill.client.lock.Locks;
import mill.client.lock.TryLocked;
import mill.constants.InputPumper;
import mill.constants.ProxyStream;
import mill.constants.ServerFiles;
import mill.constants.Util;

/**
 * Client side code that interacts with `Server.scala` in order to launch a generic
 * long-lived background server.
 *
 * The protocol is as follows:
 *
 * - Client:
 *   - Take clientLock
 *   - If processLock is not yet taken, it means server is not running, so spawn a server
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take processLock.
 *     - If already taken, it means another server was running
 *       (e.g. spawned by a different client) so exit immediately
 * - Server: loop:
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If clientLock is released during execution, terminate server (otherwise
 *     we have no safe way of terminating the in-process request, so the server
 *     may continue running for arbitrarily long with no client attached)
 *   - Send `ProxyStream.END` packet and call `clientSocket.close()`
 * - Client:
 *   - Wait for `ProxyStream.END` packet or `clientSocket.close()`,
 *     indicating server has finished execution and all data has been received
 */
public abstract class ServerLauncher {
  public static class Result {
    public int exitCode;
    public Path serverDir;
  }

  final int serverProcessesLimit = 5;
  final int serverInitWaitMillis = 10000;

  public abstract void initServer(Path serverDir, boolean b, Locks locks) throws Exception;

  public abstract void preRun(Path serverDir) throws Exception;

  InputStream stdin;
  PrintStream stdout;
  PrintStream stderr;
  Map<String, String> env;
  String[] args;
  Locks[] memoryLocks;
  int forceFailureForTestingMillisDelay;

  public ServerLauncher(
      InputStream stdin,
      PrintStream stdout,
      PrintStream stderr,
      Map<String, String> env,
      String[] args,
      Locks[] memoryLocks,
      int forceFailureForTestingMillisDelay) {
    this.stdin = stdin;
    this.stdout = stdout;
    this.stderr = stderr;
    this.env = env;
    this.args = args;

    // For testing in memory, we need to pass in the locks separately, so that the
    // locks can be shared between the different instances of `ServerLauncher` the
    // same way file locks are shared between different Mill client/server processes
    this.memoryLocks = memoryLocks;

    this.forceFailureForTestingMillisDelay = forceFailureForTestingMillisDelay;
  }

  public Result acquireLocksAndRun(Path serverDir0) throws Exception {

    final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
    if (setJnaNoSys) {
      System.setProperty("jna.nosys", "true");
    }

    int serverIndex = 0;
    while (serverIndex < serverProcessesLimit) { // Try each possible server process (-1 to -5)
      serverIndex++;
      final Path serverDir =
          serverDir0.getParent().resolve(serverDir0.getFileName() + "-" + serverIndex);

      Files.createDirectories(serverDir);

      try (Locks locks = memoryLocks != null
              ? memoryLocks[serverIndex - 1]
              : Locks.files(serverDir.toString());
          TryLocked clientLocked = locks.clientLock.tryLock()) {
        if (clientLocked.isLocked()) {
          Result result = new Result();
          preRun(serverDir);
          result.exitCode = run(serverDir, setJnaNoSys, locks);
          result.serverDir = serverDir;
          return result;
        }
      }
    }
    throw new ServerCouldNotBeStarted(
        "Reached max server processes limit: " + serverProcessesLimit);
  }

  int run(Path serverDir, boolean setJnaNoSys, Locks locks) throws Exception {

    try (OutputStream f = Files.newOutputStream(serverDir.resolve(ServerFiles.runArgs))) {
      f.write(Util.hasConsole() ? 1 : 0);
      ClientUtil.writeString(f, BuildInfo.millVersion);
      ClientUtil.writeArgs(args, f);
      ClientUtil.writeMap(env, f);
    }

    if (locks.processLock.probe()) initServer(serverDir, setJnaNoSys, locks);

    while (locks.processLock.probe()) Thread.sleep(1);

    long retryStart = System.currentTimeMillis();
    Socket ioSocket = null;
    Throwable socketThrowable = null;
    while (ioSocket == null && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      try {
        int port = Integer.parseInt(Files.readString(serverDir.resolve(ServerFiles.socketPort)));
        ioSocket = new java.net.Socket(InetAddress.getLoopbackAddress(), port);
      } catch (Throwable e) {
        socketThrowable = e;
        Thread.sleep(1);
      }
    }

    if (ioSocket == null) {
      throw new Exception("Failed to connect to server", socketThrowable);
    }

    InputStream outErr = ioSocket.getInputStream();
    OutputStream in = ioSocket.getOutputStream();
    ProxyStream.Pumper outPumper = new ProxyStream.Pumper(outErr, stdout, stderr);
    InputPumper inPump = new InputPumper(() -> stdin, () -> in, true);
    Thread outPumperThread = new Thread(outPumper, "outPump");
    outPumperThread.setDaemon(true);
    Thread inThread = new Thread(inPump, "inPump");
    inThread.setDaemon(true);
    outPumperThread.start();
    inThread.start();

    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay);
      throw new Exception("Force failure for testing: " + serverDir);
    }
    outPumperThread.join();

    try {
      Path exitCodeFile = serverDir.resolve(ServerFiles.exitCode);
      if (Files.exists(exitCodeFile)) {
        return Integer.parseInt(Files.readAllLines(exitCodeFile).get(0));
      } else {
        System.err.println("mill-server/ exitCode file not found");
        return 1;
      }
    } finally {
      ioSocket.close();
    }
  }
}
