package mill.client;

public abstract class LaunchedServer {
  public abstract boolean isAlive();
  public abstract void kill();

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

    @Override
    public void kill() {
      if (!process.destroy())
        throw new IllegalStateException("Asked " + process + " to terminate, but the termination request failed.");
    }
  }

  /// Code running in the same process.
  public static class NewThread extends LaunchedServer {
    public final Thread thread;
    final Runnable _kill;

    public NewThread(Thread thread, Runnable kill) {
      this.thread = thread;
      _kill = kill;
    }

    @Override
    public String toString() {
      return "LaunchedServer.NewThread{" + "thread=" + thread + '}';
    }

    @Override
    public boolean isAlive() {
      return thread.isAlive();
    }

    @Override
    public void kill() { _kill.run(); }
  }
}
