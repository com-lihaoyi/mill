package mill.client;

import java.util.function.Function;

public abstract class ServerLaunchResult {
  public abstract <R> R fold(
      Function<Success, R> onSuccess,
      Function<AlreadyRunning, R> onAlreadyRunning,
      Function<CouldNotStart, R> onCouldNotStart,
      Function<ProcessDied, R> onProcessDied);

  /// The server process was not running, so it was started and is now running.
  public static class Success extends ServerLaunchResult {
    /// The server process.
    public final Process process;

    public Success(Process process) {
      this.process = process;
    }

    @Override
    public <R> R fold(
        Function<Success, R> onSuccess,
        Function<AlreadyRunning, R> onAlreadyRunning,
        Function<CouldNotStart, R> onCouldNotStart,
        Function<ProcessDied, R> onProcessDied) {
      return onSuccess.apply(this);
    }
  }

  /// The server process was already running.
  public static class AlreadyRunning extends ServerLaunchResult {
    @Override
    public <R> R fold(
        Function<Success, R> onSuccess,
        Function<AlreadyRunning, R> onAlreadyRunning,
        Function<CouldNotStart, R> onCouldNotStart,
        Function<ProcessDied, R> onProcessDied) {
      return onAlreadyRunning.apply(this);
    }
  }

  /// We tried to start the server, but it failed in the process launch phase.
  public static class CouldNotStart extends ServerLaunchResult {
    public final ServerLaunchOutputs outputs;

    public CouldNotStart(ServerLaunchOutputs outputs) {
      this.outputs = outputs;
    }

    @Override
    public String toString() {
      return "CouldNotStart{outputs=" + outputs + '}';
    }

    @Override
    public <R> R fold(
        Function<Success, R> onSuccess,
        Function<AlreadyRunning, R> onAlreadyRunning,
        Function<CouldNotStart, R> onCouldNotStart,
        Function<ProcessDied, R> onProcessDied) {
      return onCouldNotStart.apply(this);
    }
  }

  /// We tried to start the server, the process has started but has died unexpectedly.
  public static class ProcessDied extends ServerLaunchResult {
    /// The server process we tried to start.
    public final Process process;

    /// The failure details
    public final ServerLaunchOutputs outputs;

    public ProcessDied(Process process, ServerLaunchOutputs outputs) {
      this.process = process;
      this.outputs = outputs;
    }

    @Override
    public String toString() {
      return "ProcessDied{" + "process=" + process + ", outputs=" + outputs + '}';
    }

    @Override
    public <R> R fold(
        Function<Success, R> onSuccess,
        Function<AlreadyRunning, R> onAlreadyRunning,
        Function<CouldNotStart, R> onCouldNotStart,
        Function<ProcessDied, R> onProcessDied) {
      return onProcessDied.apply(this);
    }
  }
}
