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
import mill.constants.DaemonFiles;
import mill.constants.InputPumper;
import mill.constants.ProxyStream;
import mill.constants.Util;

/**
 * Client side code that interacts with `Server.scala` in order to launch a generic
 * long-lived background daemon.
 *
 * The protocol is as follows:
 *
 * - Client:
 *   - Take launcherLock
 *   - If daemonLock is not yet taken, it means server is not running, so spawn a server
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take daemonLock.
 *     - If already taken, it means another server was running
 *       (e.g. spawned by a different client) so exit immediately
 * - Server: loop:
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If launcherLock is released during execution, terminate server (otherwise
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
    public Path daemonDir;
  }

  final int serverInitWaitMillis = 10000;

  /**
   * Starts a Mill server
   *
   * @return the server process if available, or null
   */
  public abstract Process initServer(Path daemonDir, Locks locks) throws Exception;

  public abstract void preparedaemonDir(Path daemonDir) throws Exception;

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

  public Result run(Path daemonDir, String javaHome) throws Exception {

    Files.createDirectories(daemonDir);

    preparedaemonDir(daemonDir);

    Socket ioSocket = launchConnectToServer(daemonDir);

    Result result = new Result();
    try {
      PumperThread outPumperThread = startStreamPumpers(ioSocket, javaHome);
      forceTestFailure(daemonDir);
      outPumperThread.join();
      result.exitCode = outPumperThread.exitCode();
      result.daemonDir = daemonDir;
    } finally {
      ioSocket.close();
    }

    return result;
  }

  Socket launchConnectToServer(Path daemonDir) throws Exception {

    try (Locks locks = memoryLock != null ? memoryLock : Locks.files(daemonDir.toString());
        mill.client.lock.Locked locked = locks.launcherLock.lock()) {

      Process daemonProcess = null;

      if (locks.daemonLock.probe()) daemonProcess = initServer(daemonDir, locks);
      while (locks.daemonLock.probe()) {
        if (daemonProcess != null && !daemonProcess.isAlive()) {
          System.err.println("Mill daemon exited unexpectedly!");
          Path stdout = daemonDir.toAbsolutePath().resolve(DaemonFiles.stdout);
          Path stderr = daemonDir.toAbsolutePath().resolve(DaemonFiles.stderr);
          if (Files.exists(stdout) && Files.size(stdout) > 0) {
            System.err.println("Daemon stdout:");
            System.err.println();
            System.err.write(Files.readAllBytes(stdout));
          } else {
            System.err.println("No daemon stdout");
          }
          if (Files.exists(stderr) && Files.size(stderr) > 0) {
            System.err.println();
            System.err.println("Daemon stderr:");
            System.err.println();
            System.err.write(Files.readAllBytes(stderr));
          } else {
            System.err.println("No daemon stderr");
          }
          System.exit(1);
        }
        Thread.sleep(1);
      }
    }
    long retryStart = System.currentTimeMillis();
    Socket ioSocket = null;
    Throwable socketThrowable = null;
    while (ioSocket == null && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      try {
        int port = Integer.parseInt(Files.readString(daemonDir.resolve(DaemonFiles.socketPort)));
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

  private void forceTestFailure(Path daemonDir) throws Exception {
    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay);
      throw new Exception("Force failure for testing: " + daemonDir);
    }
  }

  class PumperThread extends Thread {
    ProxyStream.Pumper runnable;

    public PumperThread(ProxyStream.Pumper runnable, String name) {
      super(runnable, name);
      this.runnable = runnable;
    }

    public int exitCode() {
      return runnable.exitCode;
    }
  }

  PumperThread startStreamPumpers(Socket ioSocket, String javaHome) throws Exception {
    InputStream outErr = ioSocket.getInputStream();
    OutputStream in = ioSocket.getOutputStream();
    in.write(Util.hasConsole() ? 1 : 0);
    ClientUtil.writeString(in, BuildInfo.millVersion);
    ClientUtil.writeString(in, javaHome);
    ClientUtil.writeArgs(args, in);
    ClientUtil.writeMap(env, in);
    ClientUtil.writeMap(ClientUtil.getUserSetProperties(), in);
    ProxyStream.Pumper outPumper = new ProxyStream.Pumper(outErr, stdout, stderr);
    InputPumper inPump = new InputPumper(() -> stdin, () -> in, true);
    PumperThread outPumperThread = new PumperThread(outPumper, "outPump");
    outPumperThread.setDaemon(true);
    Thread inThread = new Thread(inPump, "inPump");
    inThread.setDaemon(true);
    outPumperThread.start();
    inThread.start();
    return outPumperThread;
  }
}
