package mill.client;

import java.util.Optional;

public class ServerLaunchResult {
  /// The server process which was used.
  public final Process process;

  /// Is present if the server failed to start.
  public final Optional<ServerLaunchFailure> failure;

  public ServerLaunchResult(Process process, Optional<ServerLaunchFailure> failure) {
    this.process = process;
    this.failure = failure;
  }
}
