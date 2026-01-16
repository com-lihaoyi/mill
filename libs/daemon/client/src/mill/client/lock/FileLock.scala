package mill.client.lock

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import scala.compiletime.uninitialized

class FileLock(path: String) extends Lock {
  private var raf: RandomAccessFile = uninitialized
  private var chan: FileChannel = uninitialized

  initializeIfNeeded()

  // Sometimes thread interruption causes these files or channels to be
  // closed unexpectedly, so if that happens just re-open them before use
  private def initializeIfNeeded(): Unit = {
    if (chan == null || !chan.isOpen) {
      raf = new RandomAccessFile(path, "rw")
      chan = raf.getChannel
    }
  }

  override def toString: String =
    s"FileLock{@${Integer.toHexString(hashCode)}, path='$path'}"

  override def lock(): Locked = {
    initializeIfNeeded()
    new FileLocked(chan.lock())
  }

  override def tryLock(): TryLocked = {
    initializeIfNeeded()
    new FileTryLocked(chan.tryLock())
  }

  override def probe(): Boolean = {
    initializeIfNeeded()
    val l = chan.tryLock()
    if (l == null) false
    else {
      l.release()
      true
    }
  }

  override def close(): Unit = {
    chan.close()
    raf.close()
  }

  override def delete(): Unit = close()
}

private[lock] class FileLocked(lock: java.nio.channels.FileLock) extends Locked {
  override def release(): Unit = lock.release()
}

private[lock] class FileTryLocked(lock: java.nio.channels.FileLock) extends FileLocked(lock) with TryLocked {
  override def isLocked: Boolean = lock != null
  override def release(): Unit = if (isLocked) super.release()
}
