package mill.client.lock;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class FileLock extends Lock {

  private RandomAccessFile raf;
  private FileChannel chan;
  private final String path;

  // Sometimes thread interruption causing these files or channels to be
  // closed unexpectedly, so if that happens just re-open them before use
  public void initializeIfNeeded() throws Exception {
    if (chan == null || !chan.isOpen()) {
      raf = new RandomAccessFile(path, "rw");
      chan = raf.getChannel();
    }
  }

  public FileLock(String path) throws Exception {
    this.path = path;
    initializeIfNeeded();
  }

  @Override
  public String toString() {
    return "FileLock{@" + Integer.toHexString(hashCode()) + ", path='" + path + '\'' + '}';
  }

  @Override
  public Locked lock() throws Exception {
    initializeIfNeeded();
    return new FileLocked(chan.lock());
  }

  @Override
  public TryLocked tryLock() throws Exception {
    initializeIfNeeded();
    return new FileTryLocked(chan.tryLock());
  }

  @Override
  public boolean probe() throws Exception {
    initializeIfNeeded();
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
