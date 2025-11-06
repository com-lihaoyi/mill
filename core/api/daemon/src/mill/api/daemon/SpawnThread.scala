package mill.api.daemon

object StartThread {
  def apply[T](name: String, daemon: Boolean = false)(block: => T) = {
    val t = new Thread((() => block): Runnable, name)
    if (daemon) t.setDaemon(true)
    t
  }
}
