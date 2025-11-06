package mill.client.lock;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class FileLock extends Lock {

  private RandomAccessFile raf;
  private FileChannel chan;
  private final String path;

  public void initialize() throws Exception {
    raf = new RandomAccessFile(path, "rw");
    chan = raf.getChannel();
  }
  public FileLock(String path) throws Exception {
    this.path = path;
    initialize();
  }

  @Override
  public String toString() {
    return "FileLock{@" + Integer.toHexString(hashCode()) + ", path='" + path + '\'' + '}';
  }

  @Override
  public Locked lock() throws Exception {
    if (!chan.isOpen()) initialize();
    return new FileLocked(chan.lock());
  }

  @Override
  public TryLocked tryLock() throws Exception {
    if (!chan.isOpen()) initialize();
    return new FileTryLocked(chan.tryLock());
  }

  @Override
  public boolean probe() throws Exception {
    if (!chan.isOpen()) initialize();
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
