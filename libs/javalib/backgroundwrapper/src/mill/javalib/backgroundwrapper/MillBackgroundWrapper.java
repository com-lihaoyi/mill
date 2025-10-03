package mill.javalib.backgroundwrapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

@SuppressWarnings("BusyWait")
public class MillBackgroundWrapper {
  private static final long NS_IN_S = 1_000_000_000;

  public static void main(String[] args) throws Exception {
    var newestProcessIdPath = Paths.get(args[0]);
    var currentlyRunningProccesIdPath = Paths.get(args[1]);
    var procLockfile = Paths.get(args[2]);
    var logPath = Paths.get(args[3]);
    var realMain = args[4];
    var realArgs = java.util.Arrays.copyOfRange(args, 5, args.length);

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
    log(logPath, myPid, "Starting. Writing my PID to " + newestProcessIdPath);
    Files.writeString(
        newestProcessIdPath,
        myPidStr,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);

    // Take a lock on `procLockfile` to ensure that only one
    // `runBackground` process  is running at any point in time.
    log(logPath, myPid, "Acquiring lock on " + procLockfile);

    //noinspection resource - this is intentional, file is released when process dies.
    RandomAccessFile raf = new RandomAccessFile(procLockfile.toFile(), "rw");
    FileChannel chan = raf.getChannel();
    if (chan.tryLock() == null) {
      var startTimeNanos = System.nanoTime();
      System.err.println("[mill:runBackground] Waiting for runBackground lock to be available");
      // this is intentional, lock is released when process dies.
      //noinspection ResultOfMethodCallIgnored
      chan.lock();
      var delta = (System.nanoTime() - startTimeNanos) / NS_IN_S;
      System.err.println("[mill:runBackground] Lock acquired after " + delta + "s");
    }
    log(logPath, myPid, "Lock acquired");

    var oldProcessPid = readPreviousPid(currentlyRunningProccesIdPath);
    log(logPath, myPid, "Old process PID: " + oldProcessPid);
    oldProcessPid.ifPresent((oldPid) -> {
      var startTimeNanos = System.nanoTime();
      log(logPath, myPid, "Waiting for old process to terminate");
      var processExisted = waitForPreviousProcessToTerminate(oldPid);
      if (processExisted) {
        log(
            logPath,
            myPid,
            "Old process terminated in " + (System.nanoTime() - startTimeNanos) / NS_IN_S + "s");
      } else {
        log(logPath, myPid, "Old process was already terminated.");
      }
    });

    log(logPath, myPid, "Writing my PID to " + currentlyRunningProccesIdPath);
    Files.writeString(
        currentlyRunningProccesIdPath,
        myPidStr,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);

    // Start the thread to watch for updates on the process marker file,
    // so we can exit if it is deleted or replaced
    var startTimeNanos = System.nanoTime(); // use nanoTime as it is monotonic
    checkIfWeStillNeedToBeRunning(logPath, startTimeNanos, newestProcessIdPath, myPid);
    var watcher = new Thread(() -> {
      while (true)
        checkIfWeStillNeedToBeRunning(logPath, startTimeNanos, newestProcessIdPath, myPid);
    });
    watcher.setDaemon(true);
    watcher.start();

    // Actually start the Java main method we wanted to run in the background
    if (!realMain.equals("<subprocess>")) {
      log(
          logPath,
          myPid,
          "Running main method " + realMain + " with args " + Arrays.toString(realArgs));
      Class.forName(realMain).getMethod("main", String[].class).invoke(null, (Object) realArgs);
    } else {
      log(logPath, myPid, "Running subprocess with args " + Arrays.toString(realArgs));
      Process subprocess = new ProcessBuilder().command(realArgs).inheritIO().start();
      log(
          logPath,
          myPid,
          "Subprocess started with PID " + subprocess.pid() + ", waiting for it to exit");

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        log(logPath, myPid, "Shutdown hook called, terminating subprocess");
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
          log(logPath, myPid, "Forcing subprocess termination");
          subprocess.destroyForcibly();
        }
      }));

      log(logPath, myPid, "Waiting for subprocess to terminate");
      var exitCode = subprocess.waitFor();
      log(logPath, myPid, "Subprocess terminated with exit code " + exitCode);
      System.exit(exitCode);
    }
  }

  private static void checkIfWeStillNeedToBeRunning(
      Path logPath, long startTimeNanos, Path newestProcessIdPath, long myPid) {
    var delta = (System.nanoTime() - startTimeNanos) / NS_IN_S;
    var myPidStr = "" + myPid;
    try {
      Thread.sleep(50);
      var token = Files.readString(newestProcessIdPath);
      if (!myPidStr.equals(token)) {
        log(
            logPath,
            myPid,
            "New process started, exiting. Token file (" + newestProcessIdPath + ") contents: \""
                + token + "\"");
        System.err.println(
            "[mill:runBackground] Background process has been replaced with a new process (PID: "
                + token + "), exiting after " + delta + "s");
        System.exit(0);
      }
    } catch (Exception e) {
      log(logPath, myPid, "Check if we still need to be running failed, exiting. Exception: " + e);
      System.err.println("[mill:runBackground] Background process exiting after " + delta + "s");
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

  /** Returns true if the process with the given PID has terminated, false if the process did not exist. */
  static boolean waitForPreviousProcessToTerminate(long pid) {
    var maybeOldProcess = ProcessHandle.of(pid);
    if (maybeOldProcess.isEmpty()) return false;
    var oldProcess = maybeOldProcess.get();

    try {
      while (oldProcess.isAlive()) {
        Thread.sleep(50);
      }
      return true;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static void log(Path logFile, long myPid, String message) {
    var timestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
    try {
      Files.writeString(
          logFile,
          "[" + timestamp + "] " + myPid + ": " + message + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      // do nothing
    }
  }
}
