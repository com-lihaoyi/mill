package mill.client

enum LaunchedServer {
  def isAlive: Boolean = this match {
    case OsProcess(process) => process.isAlive
    case NewThread(thread, _) => thread.isAlive
    case TestStub => throw new RuntimeException("not implemented, this should never happen")
  }

  /** An operating system process was launched. */
  case OsProcess(process: ProcessHandle)

  /** Code running in the same process. */
  case NewThread(thread: Thread, kill: () => Unit)

  /** Test-only stub used when PID < 0 */
  case TestStub
}
