package mill.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mill.client.lock.Locked;
import mill.client.lock.Locks;
import mill.constants.DaemonFiles;
import mill.constants.InputPumper;
import mill.constants.ProxyStream;
import mill.constants.Util;

/// Client side code that interacts with `Server.scala` in order to launch a generic
/// long-lived background daemon.
///
/// The protocol is as follows:
/// - Client:
///   - Take launcherLock
///   - If daemonLock is not yet taken, it means server is not running, so spawn a server
///   - Wait for server socket to be available for connection
/// - Server:
///   - Take daemonLock.
///     - If already taken, it means another server was running
///       (e.g. spawned by a different client) so exit immediately
/// - Server: loop:
///   - Listen for incoming client requests on serverSocket
///   - Execute client request
///   - If launcherLock is released during execution, terminate server (otherwise
///     we have no safe way of terminating the in-process request, so the server
///     may continue running for arbitrarily long with no client attached)
///   - Send `ProxyStream.END` packet and call `clientSocket.close()`
/// - Client:
///   - Wait for `ProxyStream.END` packet or `clientSocket.close()`,
///     indicating server has finished execution and all data has been received
public abstract class ServerLauncher {
  public static class Result {
    public final int exitCode;
    public final Path daemonDir;

    public Result(int exitCode, Path daemonDir) {
      this.exitCode = exitCode;
      this.daemonDir = daemonDir;
    }
  }

  final int serverInitWaitMillis = 10000;

  public interface InitServer {
    /** Initializes the server, returning the server process or null. */
    Process init() throws Exception;
  }

  public interface RunClientLogic {
    /** Runs the client logic.
     *
     * @param rawServerStdin raw access to the server's stdin, you should only use this if you know what you're doing,
     *                       use {@link Streams} to communicate with the server instead. */
    void run(OutputStream rawServerStdin) throws Exception;
  }

  public static Result run(
    Path daemonDir,
    Locks locks,
    int serverInitWaitMillis,
    InitServer initServer,
    Streams streams,
    RunClientLogic runClientLogic)
    throws Exception {
    Files.createDirectories(daemonDir);

    try (var ioSocket =
           launchOrConnectToServer(locks, daemonDir, serverInitWaitMillis, initServer)) {
      var socketInputStream = ioSocket.getInputStream();
      var socketOutputStream = ioSocket.getOutputStream();
      var pumperThread = startStreamPumpers(socketInputStream, socketOutputStream, streams);
      runClientLogic.run(socketOutputStream);
      pumperThread.join();
      return new Result(pumperThread.exitCode(), daemonDir);
    }
  }

  /**
   * Establishes a connection to the Mill server by acquiring necessary locks and potentially
   * starting a new server process if one is not already running.
   *
   * @param daemonDir the directory where daemon-related files are stored
   * @param serverInitWaitMillis maximum amount of time to wait for the server to start
   * @return a Socket connected to the Mill server
   * @throws Exception if the server fails to start or a connection cannot be established
   */
  public static Socket launchOrConnectToServer(
    Locks locks, Path daemonDir, int serverInitWaitMillis, InitServer initServer)
    throws Exception {
    try (Locked ignored = locks.launcherLock.lock()) {
      Process daemonProcess = null;

      if (locks.daemonLock.probe()) daemonProcess = initServer.init();

      while (locks.daemonLock.probe()) {
        if (daemonProcess != null && !daemonProcess.isAlive()) {
          System.err.println("Daemon exited unexpectedly!");
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
        //noinspection BusyWait
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
        //noinspection BusyWait
        Thread.sleep(1);
      }
    }

    if (ioSocket == null) {
      throw new Exception("Failed to connect to server", socketThrowable);
    }

    return ioSocket;
  }

  public static class Streams {
    /// The input stream to send to the server as the stdin.
    public final InputStream stdin;

    /// Server's stdout will be written to this output stream.
    public final OutputStream stdout;

    /// Server's stderr will be written to this output stream.
    public final OutputStream stderr;

    public Streams(InputStream stdin, OutputStream stdout, OutputStream stderr) {
      this.stdin = stdin;
      this.stdout = stdout;
      this.stderr = stderr;
    }
  }

  static class PumperThread extends Thread {
    final ProxyStream.Pumper runnable;

    public PumperThread(ProxyStream.Pumper runnable, String name) {
      super(runnable, name);
      this.runnable = runnable;
    }

    public int exitCode() {
      return runnable.exitCode;
    }
  }

  /**
   * Starts the stream pumpers for the given socket connection to handle input and output streams.
   *
   * @param socketInputStream the input stream from the server
   * @param socketOutputStream the output stream to the server
   * @return a PumperThread that processes the output/error streams from the server
   */
  static PumperThread startStreamPumpers(
    InputStream socketInputStream, OutputStream socketOutputStream, Streams streams) {
    var outPumper = new ProxyStream.Pumper(socketInputStream, streams.stdout, streams.stderr);
    var inPump = new InputPumper(() -> streams.stdin, () -> socketOutputStream, true);
    var outPumperThread = new PumperThread(outPumper, "outPump");
    outPumperThread.setDaemon(true);
    var inThread = new Thread(inPump, "inPump");
    inThread.setDaemon(true);
    outPumperThread.start();
    inThread.start();
    return outPumperThread;
  }
}
