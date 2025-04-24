package mill.define.internal

object ResolveChecker extends os.Checker {
  def onRead(path: os.ReadablePath): Unit = ()

  def onWrite(path: os.Path): Unit = {
    sys.error(s"Writing to $path not allowed during resolution phase")
  }

  def withResolveChecker[T](f: () => T): T = {
    os.checker.withValue(this) {
      f()
    }
  }

}
