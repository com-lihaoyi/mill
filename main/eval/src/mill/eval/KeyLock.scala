package mill.eval

private class KeyLock[K]() {
  private[this] val lockKeys = new java.util.HashSet[K]()

  def lock(key: K): AutoCloseable = {
    lockKeys.synchronized {
      while (!lockKeys.add(key)) {
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
