package mill.client;

import java.io.IOException;
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
 *   - If serverLock is not yet taken, it means server is not running, so spawn a server
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take serverLock.
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

  public abstract void initServer(Path serverDir, Locks locks) throws Exception;

  public abstract void prepareServerDir(Path serverDir) throws Exception;

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

  public Result run(Path serverDir) throws Exception {

    Files.createDirectories(serverDir);

    prepareServerDir(serverDir);

    Socket ioSocket = launchConnectToServer(serverDir);

    try {
      Thread outPumperThread = startStreamPumpers(ioSocket);
      forceTestFailure(serverDir);
      outPumperThread.join();
    } finally {
      ioSocket.close();
    }

    Result result = new Result();
    result.exitCode = readExitCode(serverDir);
    result.serverDir = serverDir;
    return result;
  }

  Socket launchConnectToServer(Path serverDir) throws Exception {

    try (Locks locks = memoryLock != null ? memoryLock : Locks.files(serverDir.toString());
        mill.client.lock.Locked locked = locks.clientLock.lock()) {

      if (locks.serverLock.probe()) initServer(serverDir, locks);
      while (locks.serverLock.probe()) Thread.sleep(1);
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
    return ioSocket;
  }

  private void forceTestFailure(Path serverDir) throws Exception {
    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay);
      throw new Exception("Force failure for testing: " + serverDir);
    }
  }

  Thread startStreamPumpers(Socket ioSocket) throws Exception {
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
    return outPumperThread;
  }

  int readExitCode(Path serverDir) throws IOException {
    Path exitCodeFile = serverDir.resolve(ServerFiles.exitCode);
    if (Files.exists(exitCodeFile)) {
      return Integer.parseInt(Files.readAllLines(exitCodeFile).get(0));
    } else {
      System.err.println("mill-server/ exitCode file not found");
      return 1;
    }
  }
}
