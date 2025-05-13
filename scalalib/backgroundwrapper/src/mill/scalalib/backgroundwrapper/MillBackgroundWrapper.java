package mill.scalalib.backgroundwrapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Optional;

@SuppressWarnings("BusyWait")
public class MillBackgroundWrapper {
  public static void main(String[] args) throws Exception {
    var newestProcessIdPath = Paths.get(args[0]);
    var currentlyRunningProccesIdPath = Paths.get(args[1]);
    var procLockfile = Paths.get(args[2]);
    var realMain = args[3];
    var realArgs = java.util.Arrays.copyOfRange(args, 4, args.length);

    // The following code should handle this scenario, when we have 2 processes contending for the
    // lock.
    //
    // This can happen if a new MillBackgroundWrapper is launched while the previous one is still
    // running due to rapid
    // changes in the source code.
    //
    // Process 1 starts, writes newest_pid=1, claims lock, writes currently_running_pid = 1
    // Process 2 starts, writes newest_pid=2, tries to claim lock but is blocked
    // Process 3 starts at the same time as process 2, writes newest_pid=3, tries to claim lock but
    // is blocked
    //
    // Process 1 reads newest_pid=3, terminates, releases lock
    // Process 2 claims lock, reads currently_running_pid = 1, waits for process 1 to die, writes
    // currently_running_pid = 2
    // Process 2 reads newest_pid=3, terminates, releases lock
    // Process 3 claims lock, reads currently_running_pid = 2, waits for process 2 to die, writes
    // currently_running_pid = 3, then starts
    // Process 3 reads newest_pid=3, continues running

    // Indicate to the previous process that we want to take over.
    var myPid = ProcessHandle.current().pid();
    var myPidStr = "" + myPid;
    Files.writeString(newestProcessIdPath, myPidStr, StandardOpenOption.CREATE);

    // Take a lock on `procLockfile` to ensure that only one
    // `runBackground` process  is running at any point in time.
    //noinspection resource - this is intentional, file is released when process dies.
    RandomAccessFile raf = new RandomAccessFile(procLockfile.toFile(), "rw");
    FileChannel chan = raf.getChannel();
    if (chan.tryLock() == null) {
      System.err.println("Waiting for runBackground lock to be available");
      //noinspection ResultOfMethodCallIgnored - this is intentional, lock is released when process
      // dies.
      chan.lock();
    }

    var oldProcessPid = readPreviousPid(currentlyRunningProccesIdPath);
    oldProcessPid.ifPresent(MillBackgroundWrapper::waitForPreviousProcessToTerminate);
    Files.writeString(currentlyRunningProccesIdPath, myPidStr, StandardOpenOption.CREATE);

    // Start the thread to watch for updates on the process marker file,
    // so we can exit if it is deleted or replaced
    var startTime = System.currentTimeMillis();
    checkIfWeStillNeedToBeRunning(startTime, newestProcessIdPath, myPidStr);
    var watcher = new Thread(() -> {
      while (true) checkIfWeStillNeedToBeRunning(startTime, newestProcessIdPath, myPidStr);
    });
    watcher.setDaemon(true);
    watcher.start();

    // Actually start the Java main method we wanted to run in the background
    if (!realMain.equals("<subprocess>")) {
      Class.forName(realMain).getMethod("main", String[].class).invoke(null, (Object) realArgs);
    } else {
      Process subprocess = new ProcessBuilder().command(realArgs).inheritIO().start();

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        subprocess.destroy();

        long now = System.currentTimeMillis();

        // If the process does not shut down withing 100ms kill it forcibly.
        while (subprocess.isAlive() && System.currentTimeMillis() - now < 100) {
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            // do nothing
          }
        }
        if (subprocess.isAlive()) {
          subprocess.destroyForcibly();
        }
      }));
      System.exit(subprocess.waitFor());
    }
  }

  private static void checkIfWeStillNeedToBeRunning(
      long startTime, Path newestProcessIdPath, String myPidStr) {
    long delta = (System.currentTimeMillis() - startTime) / 1000;
    try {
      Thread.sleep(50);
      String token = Files.readString(newestProcessIdPath);
      if (!token.equals(myPidStr)) {
        System.err.println("runBackground exiting after " + delta + "s");
        System.exit(0);
      }
    } catch (Exception e) {
      System.err.println("runBackground exiting after " + delta + "s");
      System.exit(0);
    }
  }

  static Optional<Long> readPreviousPid(Path pidFilePath) {
    try {
      var pidStr = Files.readString(pidFilePath);
      return Optional.of(Long.parseLong(pidStr));
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  static void waitForPreviousProcessToTerminate(long pid) {
    var maybeOldProcess = ProcessHandle.of(pid);
    if (maybeOldProcess.isEmpty()) return;
    var oldProcess = maybeOldProcess.get();

    try {
      while (oldProcess.isAlive()) {
        Thread.sleep(50);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
