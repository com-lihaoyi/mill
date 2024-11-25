package mill.main.client.lock

class FileLocked(protected val lock: java.nio.channels.FileLock) extends Locked {

  @throws[Exception]
  def release(): Unit = {
    lock.release()
  }
}
