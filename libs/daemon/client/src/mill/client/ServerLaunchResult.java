package mill.client;

import java.util.function.Function;

public abstract class ServerLaunchResult {
  public abstract <R> R fold(
    Function<Success, R> onSuccess,
    Function<AlreadyRunning, R> onAlreadyRunning,
    Function<ServerDied, R> onProcessDied
  );

  /// The server process was not running, so it was started and is now running.
  public static class Success extends ServerLaunchResult {
    /// The server process.
    public final LaunchedServer server;

    public Success(LaunchedServer server) {
      this.server = server;
    }

    @Override
    public <R> R fold(Function<Success, R> onSuccess, Function<AlreadyRunning, R> onAlreadyRunning, Function<ServerDied, R> onProcessDied) {
      return onSuccess.apply(this);
    }
  }

  /// The server process was already running.
  public static class AlreadyRunning extends ServerLaunchResult {
    @Override
    public <R> R fold(Function<Success, R> onSuccess, Function<AlreadyRunning, R> onAlreadyRunning, Function<ServerDied, R> onProcessDied) {
      return onAlreadyRunning.apply(this);
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
      return "ProcessDied{" +
        "server=" + server +
        ", outputs=" + outputs +
        '}';
    }

    @Override
    public <R> R fold(Function<Success, R> onSuccess, Function<AlreadyRunning, R> onAlreadyRunning, Function<ServerDied, R> onProcessDied) {
      return onProcessDied.apply(this);
    }
  }
}
