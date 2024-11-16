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

    Files.writeString(procUuidPath, procUuid, StandardOpenOption.CREATE);
    RandomAccessFile raf = new RandomAccessFile(procLockfile.toFile(), "rw");
    FileChannel chan = raf.getChannel();
    if (chan.tryLock() == null) {
      System.err.println("Waiting for runBackground lock to be available");
      chan.lock();
    }

    Thread.sleep(lockDelay);
    Thread watcher = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(1);
          String token = Files.readString(procUuidPath);
          if (!token.equals(procUuid)) {
            System.err.println("runBackground procUuid changed, exiting");
            System.exit(0);
          }
        } catch (Exception e) {
          System.err.println("runBackground failed to read procUuidPath, exiting");
          System.exit(0);
        }
      }
    });

    watcher.setDaemon(true);
    watcher.start();
    String realMain = args[4];
    String[] realArgs = java.util.Arrays.copyOfRange(args, 5, args.length);
    Class.forName(realMain).getMethod("main", String[].class).invoke(null, (Object) realArgs);
  }
}
