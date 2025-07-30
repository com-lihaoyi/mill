package mill.client;

import java.util.Optional;

public class ServerLaunchFailure {
  /// The stdout of the failed server (if any).
  public final Optional<String> stdout;

  /// The stderr of the failed server (if any).
  public final Optional<String> stderr;

  /// The process that failed.
  public final Process deadProcess;

  public ServerLaunchFailure(Optional<String> stdout, Optional<String> stderr, Process deadProcess) {
    this.stdout = stdout;
    this.stderr = stderr;
    this.deadProcess = deadProcess;
  }

  public String debugString() {
    var sb = new StringBuilder();
    sb.append("Daemon (PID ").append(deadProcess.pid()).append(") exited unexpectedly!\n");
    sb.append("\n");

    stdout.ifPresentOrElse(
      s -> sb.append("Daemon stdout:\n\n").append(s).append("\n\n"),
      () -> sb.append("No daemon stdout\n\n")
    );

    stderr.ifPresentOrElse(
      s -> sb.append("Daemon stderr:\n\n").append(s).append("\n\n"),
      () -> sb.append("No daemon stderr\n\n")
    );

    return sb.toString();
  }
}
