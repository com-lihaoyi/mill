package mill.main.client.lock

trait Locked extends AutoCloseable {

  @throws[Exception]
  def release(): Unit

  @throws[Exception]
  override def close(): Unit = {
    release()
  }
}
