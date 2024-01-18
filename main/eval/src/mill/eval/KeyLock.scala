package mill.eval

private class KeyLock[K]() {
  private[this] val lockKeys = new java.util.HashSet[K]()

  def lock(key: K,  onCollision: Option[() => Unit] = None): AutoCloseable = {
    lockKeys.synchronized {
      var shown = false
      while (!lockKeys.add(key)) {
        if (!shown) {
          onCollision.foreach(_())
          shown = true
        }
        lockKeys.wait();
      }
    }
    () => unlock(key)
  }

  private def unlock(key: K): Unit = {
    lockKeys.synchronized {
      lockKeys.remove(key)
      lockKeys.notifyAll()
    }
  }
}
