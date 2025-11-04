package mill.client.lock;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class FileLock extends Lock {

  private final RandomAccessFile raf;
  private final FileChannel chan;
  private final String path;

  public FileLock(String path) throws Exception {
    raf = new RandomAccessFile(path, "rw");
    chan = raf.getChannel();
    this.path = path;
  }

  @Override
  public String toString() {
    return "FileLock{@" + Integer.toHexString(hashCode()) + ", path='" + path + '\'' + '}';
  }

  @Override
  public Locked lock() throws Exception {
    return new FileLocked(chan.lock());
  }

  @Override
  public TryLocked tryLock() throws Exception {
    return new FileTryLocked(chan.tryLock());
  }

  @Override
  public boolean probe() throws Exception {
    var l = chan.tryLock();
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
