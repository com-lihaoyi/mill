package mill.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mill.client.lock.Locked;
import mill.client.lock.Locks;
import mill.constants.DaemonFiles;
import mill.constants.InputPumper;
import mill.constants.ProxyStream;

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
  final int serverInitWaitMillis = 10000;

  /// Run a client logic with an connection established to a Mill server
  /// (via {@link ServerLauncher#launchOrConnectToServer}).
  ///
  /// @param connection     the socket connected to the server
  /// @param streams        streams to use for the client logic
  /// @param runClientLogic the client logic to run
  /// @return the exit code that the server sent back
  public static int runWithConnection(
      Socket connection, Streams streams, RunClientLogic runClientLogic) throws Exception {
    var socketInputStream = connection.getInputStream();
    var socketOutputStream = connection.getOutputStream();
    var pumperThread = startStreamPumpers(socketInputStream, socketOutputStream, streams);
    runClientLogic.run(socketOutputStream);
    pumperThread.join();
    return pumperThread.exitCode();
  }

  /**
   * Establishes a connection to the Mill server by acquiring necessary locks and potentially
   * starting a new server process if one is not already running.
   *
   * @param daemonDir            the directory where daemon-related files are stored
   * @param serverInitWaitMillis maximum amount of time to wait for the server to start
   * @return a Socket connected to the Mill server
   * @throws Exception if the server fails to start or a connection cannot be established
   */
  public static Socket launchOrConnectToServer(
      Locks locks,
      Path daemonDir,
      int serverInitWaitMillis,
      InitServer initServer,
      Consumer<ServerLaunchFailure> onFailure)
      throws Exception {
    var maybeFailure = ensureServerIsRunning(locks, daemonDir, initServer);
    maybeFailure.ifPresent(onFailure);

    var startTime = System.currentTimeMillis();
    var port = readServerPort(daemonDir, startTime, serverInitWaitMillis);
    return connectToServer(startTime, serverInitWaitMillis, port);
  }

  public static Integer readServerPort(
      Path daemonDir, long startTimeMillis, int serverInitWaitMillis) throws Exception {
    return withTimeout(startTimeMillis, serverInitWaitMillis, "Failed to read server port", () -> {
      try {
        return Optional.of(
            Integer.parseInt(Files.readString(daemonDir.resolve(DaemonFiles.socketPort))));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  public static Socket connectToServer(long startTimeMillis, int serverInitWaitMillis, int port)
      throws Exception {
    return withTimeout(startTimeMillis, serverInitWaitMillis, "Failed to connect to server", () -> {
      try {
        return Optional.of(new Socket(InetAddress.getLoopbackAddress(), port));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  public static <A> A withTimeout(
      long startTimeMillis, long timeoutMillis, String errorMessage, Supplier<Optional<A>> supplier)
      throws Exception {
    var current = Optional.<A>empty();
    Throwable throwable = null;
    while (current.isEmpty() && System.currentTimeMillis() - startTimeMillis < timeoutMillis) {
      try {
        current = supplier.get();
      } catch (Throwable e) {
        throwable = e;

        //noinspection BusyWait
        Thread.sleep(1);
      }
    }

    if (current.isEmpty()) {
      throw new Exception(errorMessage, throwable);
    }
    return current.get();
  }

  /**
   * Attempts to start a server process using {@link InitServer} if that is needed.
   *
   * @param locks      the locks to use for coordination
   * @param daemonDir  the directory where the server will write its port
   * @param initServer the function to use to start the server
   * @return an {@link Optional} containing a {@link ServerLaunchFailure} if the server failed to start
   */
  public static Optional<ServerLaunchFailure> ensureServerIsRunning(
      Locks locks, Path daemonDir, InitServer initServer) throws Exception {
    Files.createDirectories(daemonDir);

    try (Locked ignored = locks.launcherLock.lock()) {
      Process daemonProcess = null;

      if (locks.daemonLock.probe()) daemonProcess = initServer.init();

      while (locks.daemonLock.probe()) {
        if (daemonProcess != null && !daemonProcess.isAlive()) {
          var stdout = daemonDir.toAbsolutePath().resolve(DaemonFiles.stdout);
          var stderr = daemonDir.toAbsolutePath().resolve(DaemonFiles.stderr);

          Optional<String> stdoutStr = Optional.empty();
          if (Files.exists(stdout) && Files.size(stdout) > 0)
            stdoutStr = Optional.of(Files.readString(stdout));

          Optional<String> stderrStr = Optional.empty();
          if (Files.exists(stderr) && Files.size(stderr) > 0)
            stderrStr = Optional.of(Files.readString(stderr));

          return Optional.of(new ServerLaunchFailure(stdoutStr, stderrStr, daemonProcess));
        }

        //noinspection BusyWait
        Thread.sleep(1);
      }
    }

    return Optional.empty();
  }

  /**
   * Starts the stream pumpers for the given socket connection to handle input and output streams.
   *
   * @param socketInputStream  the input stream from the server
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

  public interface InitServer {
    /**
     * Initializes the server process, returning it or null if it failed to start.
     */
    Process init() throws Exception;
  }

  public interface RunClientLogic {
    /**
     * Runs the client logic.
     *
     * @param rawServerStdin raw access to the server's stdin, you should only use this if you know what you're doing,
     *                       use {@link Streams} to communicate with the server instead.
     */
    void run(OutputStream rawServerStdin) throws Exception;
  }

  public static class Result {
    public final int exitCode;
    public final Path daemonDir;

    public Result(int exitCode, Path daemonDir) {
      this.exitCode = exitCode;
      this.daemonDir = daemonDir;
    }
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
}
