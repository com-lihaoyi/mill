package mill.scalalib.backgroundwrapper;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MillBackgroundWrapper {
  public static void main(String[] args) throws Exception {
    Path procUuidPath = Paths.get(args[0]);
    Path procLockfile = Paths.get(args[1]);
    String procUuid = args[2];
    int lockDelay = Integer.parseInt(args[3]);

    // Take a lock on `procLockfile` to ensure that only one
    // `runBackground` process  is running at any point in time.
    Files.writeString(procUuidPath, procUuid, StandardOpenOption.CREATE);
    RandomAccessFile raf = new RandomAccessFile(procLockfile.toFile(), "rw");
    FileChannel chan = raf.getChannel();
    if (chan.tryLock() == null) {
      System.err.println("Waiting for runBackground lock to be available");
      chan.lock();
    }

    // For some reason even after the previous process exits things like sockets
    // may still take time to free, so sleep for a configurable duration before proceeding
    Thread.sleep(lockDelay);

    // Start the thread to watch for updates on the process marker file,
    // so we can exit if it is deleted or replaced
    long startTime = System.currentTimeMillis();
    Thread watcher = new Thread(() -> {
      while (true) {
        long delta = (System.currentTimeMillis() - startTime) / 1000;
        try {
          Thread.sleep(1);
          String token = Files.readString(procUuidPath);
          if (!token.equals(procUuid)) {
            System.err.println("runBackground exiting after " + delta + "s");
            System.exit(0);
          }
        } catch (Exception e) {
          System.err.println("runBackground exiting after " + delta + "s");
          System.exit(0);
        }
      }
    });

    watcher.setDaemon(true);
    watcher.start();

    // Actually start the Java main method we wanted to run in the background
    String realMain = args[4];
    String[] realArgs = java.util.Arrays.copyOfRange(args, 5, args.length);
    Class.forName(realMain).getMethod("main", String[].class).invoke(null, (Object) realArgs);
  }
}
