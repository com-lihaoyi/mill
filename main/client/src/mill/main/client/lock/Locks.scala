package mill.main.client.lock

import mill.main.client.ServerFiles

final case class Locks(val clientLock: Lock, val processLock: Lock) extends AutoCloseable {

  @throws[Exception]
  override def close(): Unit = {
    clientLock.delete()
    processLock.delete()
  }
}

object Locks {
  @throws[Exception]
  def files(serverDir: String): Locks = {
    new Locks(
      new FileLock(s"$serverDir/${ServerFiles.clientLock}"),
      new FileLock(s"$serverDir/${ServerFiles.processLock}")
    )
  }

  def memory(): Locks = {
    new Locks(new MemoryLock(), new MemoryLock())
  }
}
