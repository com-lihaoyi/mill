package mill.client;


public abstract class ServerLaunchResult {
  /// The server process was not running, so it was started and is now running.
  public static class Success extends ServerLaunchResult {
    /// The server process.
    public final LaunchedServer server;

    public Success(LaunchedServer server) {
      this.server = server;
    }
  }

  /// The server process was already running.
  public static class AlreadyRunning extends ServerLaunchResult {
    /// The server process.
    public final LaunchedServer server;

    public AlreadyRunning(LaunchedServer server) {
      this.server = server;
    }

  }

  /// We tried to start the server, the process has started but has died unexpectedly.
  public static class ServerDied extends ServerLaunchResult {
    /// The server process we tried to start.
    public final LaunchedServer server;

    /// The failure details
    public final ServerLaunchOutputs outputs;

    public ServerDied(LaunchedServer server, ServerLaunchOutputs outputs) {
      this.server = server;
      this.outputs = outputs;
    }

    @Override
    public String toString() {
      return "ProcessDied{" + "server=" + server + ", outputs=" + outputs + '}';
    }
  }
}
