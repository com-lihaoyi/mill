package mill.clientserver

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.locks.{ReadWriteLock, ReentrantLock}


trait Lock{
  def lock(): Locked
  def lockBlock[T](t: => T): T = {
    val l = lock()
    try t
    finally l.release()
  }
  def tryLockBlock[T](t: => T): Option[T] = {
    tryLock() match{
      case None =>
        None
      case Some(l) =>
        try Some(t)
        finally l.release()
    }

  }
  def tryLock(): Option[Locked]
  def await(): Unit = {
    val l = lock()
    l.release()
  }

  /**
    * Returns `true` if the lock is *available for taking*
    */
  def probe(): Boolean
}
trait Locked{
  def release(): Unit
}
trait Locks{
  val processLock: Lock
  val serverLock: Lock
  val clientLock: Lock
}
class FileLocked(lock: java.nio.channels.FileLock) extends Locked{
  def release() = {
    lock.release()
  }
}

class FileLock(path: String) extends Lock{

  val raf = new RandomAccessFile(path, "rw")
  val chan = raf.getChannel
  def lock() = {
    val lock = chan.lock()
    new FileLocked(lock)
  }
  def tryLock() = {
    chan.tryLock() match{
      case null => None
      case lock => Some(new FileLocked(lock))
    }
  }
  def probe(): Boolean = tryLock() match{
    case None => false
    case Some(locked) =>
      locked.release()
      true
  }
}
class FileLocks(lockBase: String) extends Locks{
  val processLock = new FileLock(lockBase + "/pid")

  val serverLock = new FileLock(lockBase + "/serverLock")

  val clientLock = new FileLock(lockBase + "/clientLock")
}
class MemoryLocked(l: java.util.concurrent.locks.Lock) extends Locked{
  def release() = l.unlock()
}

class MemoryLock() extends Lock{
  val innerLock = new ReentrantLock(true)

  def probe() = !innerLock.isLocked
  def lock() = {
    innerLock.lock()
    new MemoryLocked(innerLock)
  }
  def tryLock() = {
    innerLock.tryLock() match{
      case false => None
      case true => Some(new MemoryLocked(innerLock))
    }
  }
}
class MemoryLocks() extends Locks{
  val processLock = new MemoryLock()

  val serverLock = new MemoryLock()

  val clientLock = new MemoryLock()
}