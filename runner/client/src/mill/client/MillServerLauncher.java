package mill.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mill.client.lock.Locks;
import mill.constants.Util;

/// Mill daemon specific {@link ServerLauncher}.
public abstract class MillServerLauncher extends ServerLauncher {
  final int serverInitWaitMillis = 10000;

  /**
   * Starts a Mill server
   *
   * @return the server process if available, or null
   */
  public abstract Process initServer(Path daemonDir, Locks locks) throws Exception;

  public abstract void prepareDaemonDir(Path daemonDir) throws Exception;

  final Streams streams;
  final Map<String, String> env;
  final String[] args;
  final Locks memoryLock;
  final int forceFailureForTestingMillisDelay;

  public MillServerLauncher(
      Streams streams,
      Map<String, String> env,
      String[] args,
      Locks memoryLock,
      int forceFailureForTestingMillisDelay) {
    this.streams = streams;
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
    prepareDaemonDir(daemonDir);

    var initData = new ClientInitData(
        /* interactive */ Util.hasConsole(),
        BuildInfo.millVersion,
        javaHome,
        args,
        env,
        ClientUtil.getUserSetProperties());
    var locks = memoryLock != null ? memoryLock : Locks.files(daemonDir.toString());

    return run(
        daemonDir,
        locks,
        serverInitWaitMillis,
        () -> initServer(daemonDir, memoryLock),
        streams,
        rawServerStdin -> {
          initData.write(rawServerStdin);
          forceTestFailure(daemonDir);
        });
  }

  private void forceTestFailure(Path daemonDir) throws Exception {
    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay);
      throw new Exception("Force failure for testing: " + daemonDir);
    }
  }
}
