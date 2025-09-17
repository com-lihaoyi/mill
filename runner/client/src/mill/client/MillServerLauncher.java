package mill.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import mill.client.lock.Locks;
import mill.constants.Util;

/// Mill daemon specific {@link ServerLauncher}.
public abstract class MillServerLauncher extends ServerLauncher {
  final int serverInitWaitMillis = 10000;
  final Streams streams;
  final Map<String, String> env;
  final String[] args;

  /// For testing in memory, we need to pass in the locks separately, so that the
  /// locks can be shared between the different instances of `ServerLauncher` the
  /// same way file locks are shared between different Mill client/server processes
  final Optional<Locks> memoryLock;

  final int forceFailureForTestingMillisDelay;

  public MillServerLauncher(
      Streams streams,
      Map<String, String> env,
      String[] args,
      Optional<Locks> memoryLock,
      int forceFailureForTestingMillisDelay) {
    this.streams = streams;
    this.env = env;
    this.args = args;
    this.memoryLock = memoryLock;
    this.forceFailureForTestingMillisDelay = forceFailureForTestingMillisDelay;
  }

  /**
   * Starts a Mill server
   *
   * @return the server process if available, or None
   */
  public abstract LaunchedServer initServer(Path daemonDir, Locks locks) throws Exception;

  public Result run(Path daemonDir, String javaHome, Consumer<String> log) throws Exception {
    Files.createDirectories(daemonDir);

    var initData = new ClientInitData(
        /* interactive */ Util.hasConsole(),
        BuildInfo.millVersion,
        javaHome,
        args,
        env,
        ClientUtil.getUserSetProperties());

    Locks locks;
    if (memoryLock.isPresent()) {
      locks = memoryLock.get();
    } else {
      locks = Locks.files(daemonDir.toString());
    }
    log.accept("launchOrConnectToServer: " + locks);

    try (var launched = launchOrConnectToServer(
        locks,
        daemonDir,
        serverInitWaitMillis,
        () -> initServer(daemonDir, locks),
        serverDied -> {
          System.err.println("Server died during startup:");
          System.err.println(serverDied.toString());
          System.exit(1);
        },
        log,
        true /*openSocket*/)) {
      log.accept("runWithConnection: " + launched);
      var result = runWithConnection(
          "MillServerLauncher[" + launched.socket.getLocalSocketAddress() + " -> "
              + launched.socket.getRemoteSocketAddress() + "]",
          launched.socket,
          streams,
          false,
          rawServerStdin -> {
            log.accept("Sending init data: " + initData);
            try {
              initData.write(rawServerStdin);
              log.accept("Init data sent.");
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          },
          () -> {
            log.accept("running client logic");
            forceTestFailure(daemonDir, log);
            return 0;
          });
      log.accept("runWithConnection exit code: " + result.exitCode);
      return new Result(result.exitCode, daemonDir);
    }
  }

  private void forceTestFailure(Path daemonDir, Consumer<String> log) throws Exception {
    if (forceFailureForTestingMillisDelay > 0) {
      log.accept(
          "Force failure for testing in " + forceFailureForTestingMillisDelay + "ms: " + daemonDir);
      Thread.sleep(forceFailureForTestingMillisDelay);
      throw new Exception("Force failure for testing: " + daemonDir);
    } else {
      log.accept("No force failure for testing: " + daemonDir);
    }
  }
}
