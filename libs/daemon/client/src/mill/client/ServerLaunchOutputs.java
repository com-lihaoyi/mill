package mill.client;

import java.util.Optional;

public class ServerLaunchOutputs {
  /// The stdout of the server (if any).
  public final Optional<String> stdout;

  /// The stderr of the server (if any).
  public final Optional<String> stderr;

  public ServerLaunchOutputs(Optional<String> stdout, Optional<String> stderr) {
    this.stdout = stdout;
    this.stderr = stderr;
  }

  public String debugString() {
    var sb = new StringBuilder();
    stdout.ifPresentOrElse(
        s -> sb.append("Daemon stdout:\n\n").append(s).append("\n\n"),
        () -> sb.append("No daemon stdout\n\n"));
    stderr.ifPresentOrElse(
        s -> sb.append("Daemon stderr:\n\n").append(s).append("\n\n"),
        () -> sb.append("No daemon stderr\n\n"));
    return sb.toString();
  }

  @Override
  public String toString() {
    return "ServerLaunchOutputs{" + debugString() + "}";
  }
}
