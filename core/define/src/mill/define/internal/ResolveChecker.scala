package mill.define.internal

class ResolveChecker(workspace: os.Path) extends os.Checker {
  def onRead(path: os.ReadablePath): Unit = path match {
    case path: os.Path => sys.error(s"Reading from ${path.relativeTo(workspace)} not allowed during resolution phase")
    case _ =>
  }

  def onWrite(path: os.Path): Unit = {
    sys.error(s"Writing to ${path.relativeTo(workspace)} not allowed during resolution phase")
  }

  def withResolveChecker[T](f: () => T): T = {
    os.checker.withValue(this) {
      f()
    }
  }

}
