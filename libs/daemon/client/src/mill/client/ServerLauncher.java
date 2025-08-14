package mill.client;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import mill.client.lock.Lock;
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
  public static class RunWithConnectionResult<A> {
    public final A result;

    public final int exitCode;

    public RunWithConnectionResult(A result, int exitCode) {
      this.result = result;
      this.exitCode = exitCode;
    }
  }

  /// Run a client logic with a connection established to a Mill server (via [#connectToServer]).
  ///
  /// @param connection     the socket connected to the server
  /// @param streams        streams to use for the client logic
  /// @param closeConnectionAfterClientLogic whether to close the connection after running the
  // client logic
  /// @param runClientLogic the client logic to run
  /// @return the exit code that the server sent back
  public static <A> RunWithConnectionResult<A> runWithConnection(
      String debugName,
      Socket connection,
      Streams streams,
      boolean closeConnectionAfterClientLogic,
      Consumer<OutputStream> sendInitData,
      RunClientLogic<A> runClientLogic)
      throws Exception {
    var socketInputStream = new BufferedInputStream(connection.getInputStream());
    var socketOutputStream = new BufferedOutputStream(connection.getOutputStream());
    sendInitData.accept(socketOutputStream);
    socketOutputStream.flush();
    var pumperThread = startStreamPumpers(socketInputStream, socketOutputStream, streams, debugName);
    var result = runClientLogic.run();
    if (closeConnectionAfterClientLogic) socketInputStream.close();
    pumperThread.join();
    return new RunWithConnectionResult<>(result, pumperThread.exitCode());
  }

  /// Run a client logic with a connection established to a Mill server (via [#connectToServer]).
  ///
  /// @param connection     the socket connected to the server
  /// @param closeConnectionAfterClientLogic whether to close the connection after running the
  // client logic
  /// @param runClientLogic the client logic to run
  /// @return the exit code that the server sent back
  public static <A> A runWithConnection(
      String debugName,
      Socket connection,
      boolean closeConnectionAfterClientLogic,
      Consumer<OutputStream> sendInitData,
      RunClientLogicWithStreams<A> runClientLogic)
      throws Exception {
    var socketInputStream = new BufferedInputStream(connection.getInputStream());
    var socketOutputStream = new BufferedOutputStream(connection.getOutputStream());
    sendInitData.accept(socketOutputStream);
    socketOutputStream.flush();
    var result = runClientLogic.run(socketInputStream, socketOutputStream);
    if (closeConnectionAfterClientLogic) socketInputStream.close();
    return result;
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
      String debugName,
      int serverInitWaitMillis,
      InitServer initServer,
      Consumer<ServerLaunchResult.ServerDied> onFailure,
      Consumer<String> log)
      throws Exception {
    var result = ensureServerIsRunning(locks, daemonDir, initServer, log);
    return result.fold(
        success -> {
          try {
            return onServerRunning(daemonDir, debugName, serverInitWaitMillis, log);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        alreadyRunning -> {
          try {
            return onServerRunning(daemonDir, debugName, serverInitWaitMillis, log);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        processDied -> {
          onFailure.accept(processDied);
          throw new IllegalStateException(processDied.toString());
        });
  }

  /// Invoked when {@link ServerLauncher#ensureServerIsRunning} succeeds.
  public static Socket onServerRunning(
      Path daemonDir, String debugName, int serverInitWaitMillis, Consumer<String> log)
      throws Exception {
    var startTime = System.nanoTime();
    log.accept("Reading server port: " + daemonDir.toAbsolutePath());
    var port = readServerPort(daemonDir, startTime, serverInitWaitMillis);
    log.accept("Read server port, connecting: " + port);
    return connectToServer(
        startTime,
        serverInitWaitMillis,
        port,
        debugName + ". Daemon directory: " + daemonDir.toAbsolutePath());
  }

  public static Integer readServerPort(
      Path daemonDir, long startTimeMonotonicNanos, long serverInitWaitMillis) throws Exception {
    var portFile = daemonDir.resolve(DaemonFiles.socketPort);
    return withTimeout(
        startTimeMonotonicNanos,
        serverInitWaitMillis,
        "Failed to read server port from " + portFile.toAbsolutePath(),
        () -> {
          try {
            return Optional.of(Integer.parseInt(Files.readString(portFile)));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /// Connects to the Mill server at the given port.
  ///
  /// @return a socket that should then be used with [#runWithConnection]
  public static Socket connectToServer(
      long startTimeMonotonicNanos, long serverInitWaitMillis, int port, String errorMessage)
      throws Exception {
    return withTimeout(
        startTimeMonotonicNanos,
        serverInitWaitMillis,
        "Failed to connect to server within " + serverInitWaitMillis + "ms on port " + port + ". "
            + errorMessage,
        () -> {
          try {
            return Optional.of(new Socket(InetAddress.getLoopbackAddress(), port));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  public static <A> A withTimeout(
      long startTimeMonotonicNanos,
      long timeoutMillis,
      String errorMessage,
      Supplier<Optional<A>> supplier)
      throws Exception {
    var current = Optional.<A>empty();
    Throwable throwable = null;
    var timeoutNanos = timeoutMillis * 1000 * 1000;
    while (current.isEmpty() && System.nanoTime() - startTimeMonotonicNanos < timeoutNanos) {
      try {
        current = supplier.get();
      } catch (Throwable e) {
        throwable = e;

        //noinspection BusyWait
        Thread.sleep(1);
      }
    }

    if (current.isEmpty()) {
      throw new Exception(errorMessage + " (timeout was " + timeoutMillis + "ms)", throwable);
    }
    return current.get();
  }

  /// Attempts to start a server process using [InitServer] if that is needed.
  ///
  /// After this succeeds you should call [#onServerRunning].
  ///
  /// @param locks      the locks to use for coordination
  /// @param daemonDir  the directory where the server will write its port
  /// @param initServer the function to use to start the server
  public static ServerLaunchResult ensureServerIsRunning(
      Locks locks, Path daemonDir, InitServer initServer, Consumer<String> log) throws Exception {
    Files.createDirectories(daemonDir);

    log.accept("Acquiring the launcher lock: " + locks.launcherLock);
    try (var ignored = locks.launcherLock.lock()) {
      // See if the server is already running.
      log.accept("Checking if the daemon lock is available: " + locks.daemonLock);
      if (locks.daemonLock.probe()) {
        log.accept("The daemon lock is available, starting the server.");
        var launchedServer = initServer.init();

        log.accept("The server has started: " + launchedServer);

        log.accept("Waiting for the server to take the daemon lock: " + locks.daemonLock);
        var maybeLaunchFailed =
            waitUntilDaemonTakesTheLock(locks.daemonLock, daemonDir, launchedServer);
        if (maybeLaunchFailed.isPresent()) {
          var outputs = maybeLaunchFailed.get();
          log.accept("The server " + launchedServer + " failed to start: " + outputs);

          return new ServerLaunchResult.ServerDied(launchedServer, outputs);
        } else {
          log.accept(
              "The server " + launchedServer + " has taken the daemon lock: " + locks.daemonLock);
          return new ServerLaunchResult.Success(launchedServer);
        }
      } else {
        log.accept("The daemon lock is not available, there is already a server running.");
        var startTime = System.nanoTime();
        var pidFile = daemonDir.resolve(DaemonFiles.processId);
        log.accept(
            "Trying to read the process ID of a running daemon from " + pidFile.toAbsolutePath());
        var pid = withTimeout(
            startTime,
            10 * 1000,
            "Could not read the process ID from " + pidFile.toAbsolutePath(),
            () -> {
              try {
                // We need to read the contents of the file and then parse them in a loop because of
                // a race
                // condition where an empty file is created first and only then the process ID is
                // written to it,
                // and thus we can read an empty string from the file otherwise.
                var contents = Files.readString(pidFile);
                return Optional.of(Long.parseLong(contents));
              } catch (IOException | NumberFormatException e) {
                return Optional.empty();
              }
            });
        log.accept("Read PID: " + pid);

        var launchedServer =
            // PID < 0 is only used in tests.
            pid >= 0
                ? new LaunchedServer.OsProcess(ProcessHandle.of(pid)
                    .orElseThrow(
                        () -> new IllegalStateException("No process found for PID " + pid)))
                : new LaunchedServer() {
                  @Override
                  public boolean isAlive() {
                    throw new RuntimeException("not implemented, this should never happen");
                  }

                  @Override
                  public void kill() {
                    throw new RuntimeException("not implemented, this should never happen");
                  }
                };
        return new ServerLaunchResult.AlreadyRunning(launchedServer);
      }
    }
  }

  /// Busy-spins until the server process is running and has taken the `daemonLock`, returning an
  // error if the daemon
  /// process dies.
  private static Optional<ServerLaunchOutputs> waitUntilDaemonTakesTheLock(
      Lock daemonLock, Path daemonDir, LaunchedServer server) throws Exception {
    while (daemonLock.probe()) {
      var maybeLaunchFailed = checkIfLaunchFailed(daemonDir, server);
      if (maybeLaunchFailed.isPresent()) return maybeLaunchFailed;

      //noinspection BusyWait
      Thread.sleep(1);
    }

    return Optional.empty();
  }

  /// Checks if the server process has failed to start.
  private static Optional<ServerLaunchOutputs> checkIfLaunchFailed(
      Path daemonDir, LaunchedServer server) throws IOException {
    if (server.isAlive()) return Optional.empty();

    var outputs = readOutputs(daemonDir);
    return Optional.of(outputs);
  }

  /// Reads the output streams from the server process.
  private static ServerLaunchOutputs readOutputs(Path daemonDir) throws IOException {
    var stdout = daemonDir.toAbsolutePath().resolve(DaemonFiles.stdout);
    var stderr = daemonDir.toAbsolutePath().resolve(DaemonFiles.stderr);

    Optional<String> stdoutStr = Optional.empty();
    if (Files.exists(stdout) && Files.size(stdout) > 0)
      stdoutStr = Optional.of(Files.readString(stdout));

    Optional<String> stderrStr = Optional.empty();
    if (Files.exists(stderr) && Files.size(stderr) > 0)
      stderrStr = Optional.of(Files.readString(stderr));

    return new ServerLaunchOutputs(stdoutStr, stderrStr);
  }

  /**
   * Starts the stream pumpers for the given socket connection to handle input and output streams.
   *
   * @param socketInputStream  the input stream from the server
   * @param socketOutputStream the output stream to the server
   * @return a PumperThread that processes the output/error streams from the server
   */
  static PumperThread startStreamPumpers(
      InputStream socketInputStream, OutputStream socketOutputStream, Streams streams,
      String name
  ) {
    var outPumper = new ProxyStream.Pumper(socketInputStream, streams.stdout, streams.stderr);
    var inPump = new InputPumper(() -> streams.stdin, () -> socketOutputStream, true);
    var outPumperThread = new PumperThread(outPumper, "outPump-" + name);
    outPumperThread.setDaemon(true);
    var inThread = new Thread(inPump, "inPump-" + name);
    inThread.setDaemon(true);
    outPumperThread.start();
    inThread.start();
    return outPumperThread;
  }

  public interface InitServer {
    /// Initializes the server process, returning it.
    LaunchedServer init() throws Exception;
  }

  public interface RunClientLogic<A> {
    /// Runs the client logic.
    A run() throws Exception;
  }

  public interface RunClientLogicWithStreams<A> {
    /// Runs the client logic.
    A run(BufferedInputStream inStream, BufferedOutputStream outStream) throws Exception;
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
