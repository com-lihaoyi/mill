package mill.client;

public abstract class LaunchedServer {
  public abstract boolean isAlive();

  /// An operating system process was launched.
  public static class OsProcess extends LaunchedServer {
    public final ProcessHandle process;

    public OsProcess(ProcessHandle process) {
      this.process = process;
    }

    @Override
    public String toString() {
      return "LaunchedServer.OsProcess{" + "process=" + process + '}';
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }
  }

  /// Code running in the same process.
  public static class NewThread extends LaunchedServer {
    public final Thread thread;

    public NewThread(Thread thread) {
      this.thread = thread;
    }

    @Override
    public String toString() {
      return "LaunchedServer.NewThread{" + "thread=" + thread + '}';
    }

    @Override
    public boolean isAlive() {
      return thread.isAlive();
    }
  }
}
