package mill.client.lock;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;

class FileLock extends Lock {

  private final RandomAccessFile raf;
  private final FileChannel chan;
  private final String path;

  public FileLock(String path) throws Exception {
    raf = new RandomAccessFile(path, "rw");
    chan = raf.getChannel();
    this.path = path;
  }

  @Override
  public Locked lock() throws Exception {
    return new FileLocked(chan.lock());
  }

  @Override
  public TryLocked tryLock() throws Exception {
    java.nio.channels.FileLock lock = null;
    try {
      lock = chan.tryLock();
    } catch (OverlappingFileLockException ex) {
      // file already locked by this JVM
    }
    return new FileTryLocked(lock);
  }

  @Override
  public boolean probe() throws Exception {
    java.nio.channels.FileLock l = chan.tryLock();
    if (l == null) return false;
    else {
      l.release();
      return true;
    }
  }

  @Override
  public void close() throws Exception {
    chan.close();
    raf.close();
  }

  @Override
  public void delete() throws Exception {
    close();
  }
}
