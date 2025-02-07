package mill.main.client.lock

class FileTryLocked(lock: java.nio.channels.FileLock) extends FileLocked(lock) with TryLocked {

  def isLocked: Boolean = lock != null

  @throws[Exception]
  override def release(): Unit = {
    if (isLocked) super.release()
  }
}
