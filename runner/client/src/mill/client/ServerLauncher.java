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

  final int serverInitWaitMillis = 10000;

  public abstract void initServer(Path serverDir, boolean b, Locks locks) throws Exception;

  public abstract void preRun(Path serverDir) throws Exception;

  InputStream stdin;
  PrintStream stdout;
  PrintStream stderr;
  Map<String, String> env;
  String[] args;
  Locks memoryLock;
  int forceFailureForTestingMillisDelay;

  public ServerLauncher(
      InputStream stdin,
      PrintStream stdout,
      PrintStream stderr,
      Map<String, String> env,
      String[] args,
      Locks memoryLock,
      int forceFailureForTestingMillisDelay) {
    this.stdin = stdin;
    this.stdout = stdout;
    this.stderr = stderr;
    this.env = env;
    this.args = args;

    // For testing in memory, we need to pass in the locks separately, so that the
    // locks can be shared between the different instances of `ServerLauncher` the
    // same way file locks are shared between different Mill client/server processes
    this.memoryLock = memoryLock;

    this.forceFailureForTestingMillisDelay = forceFailureForTestingMillisDelay;
  }

  public Result acquireLocksAndRun(Path serverDir) throws Exception {
    final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
    if (setJnaNoSys) System.setProperty("jna.nosys", "true");

    Files.createDirectories(serverDir);

    Result result = new Result();
    preRun(serverDir);
    result.exitCode = run(serverDir, setJnaNoSys);
    result.serverDir = serverDir;
    return result;
  }

  int run(Path serverDir, boolean setJnaNoSys) throws Exception {

    try (Locks locks = memoryLock != null ? memoryLock : Locks.files(serverDir.toString());
        mill.client.lock.Locked locked = locks.clientLock.lock()) {

      if (locks.processLock.probe()) initServer(serverDir, setJnaNoSys, locks);
      while (locks.processLock.probe()) Thread.sleep(1);
    }
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
    in.write(Util.hasConsole() ? 1 : 0);
    ClientUtil.writeString(in, BuildInfo.millVersion);
    ClientUtil.writeArgs(args, in);
    ClientUtil.writeMap(env, in);
    ProxyStream.Pumper outPumper = new ProxyStream.Pumper(outErr, stdout, stderr);
    InputPumper inPump = new InputPumper(() -> stdin, () -> in, true);
    Thread outPumperThread = new Thread(outPumper, "outPump");
    outPumperThread.setDaemon(true);
    Thread inThread = new Thread(inPump, "inPump");
    inThread.setDaemon(true);
    outPumperThread.start();
    inThread.start();

    try {
      if (forceFailureForTestingMillisDelay > 0) {
        Thread.sleep(forceFailureForTestingMillisDelay);
        throw new Exception("Force failure for testing: " + serverDir);
      }
      outPumperThread.join();

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
