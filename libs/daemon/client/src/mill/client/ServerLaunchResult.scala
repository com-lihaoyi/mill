package mill.client

enum ServerLaunchResult {
  /** The server process was not running, so it was started and is now running. */
  case Success(server: LaunchedServer)

  /** The server process was already running. */
  case AlreadyRunning(server: LaunchedServer)

  /** We tried to start the server, the process has started but has died unexpectedly. */
  case ServerDied(server: LaunchedServer, outputs: ServerLaunchOutputs)
}
