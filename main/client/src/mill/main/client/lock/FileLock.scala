package mill.main.client.lock

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import scala.util.Try

class FileLock(path: String) extends Lock with AutoCloseable {
  private final val raf: RandomAccessFile = new RandomAccessFile(path, "rw")
  private final val chan: FileChannel = raf.getChannel

  @throws[Exception]
  def lock(): Locked = new FileLocked(chan.lock())

  @throws[Exception]
  def tryLock(): TryLocked = new FileTryLocked(chan.tryLock())

  @throws[Exception]
  def probe(): Boolean = {
    val lock = chan.tryLock()
    if (lock == null) false
    else {
      lock.release()
      true
    }
  }

  @throws[Exception]
  override def close(): Unit = {
    chan.close()
    raf.close()
  }

  @throws[Exception]
  override def delete(): Unit =
    close()
}
