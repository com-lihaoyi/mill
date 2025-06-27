package mill.define.internal

private[mill] class ResolveChecker(workspace: os.Path) extends os.Checker {
  def onRead(path: os.ReadablePath): Unit = {
    path match {
      case path: os.Path if mill.api.FilesystemCheckerEnabled.value =>
        sys.error(s"Reading from ${path.relativeTo(workspace)} not allowed during resolution phase")
      case _ =>
    }
  }

  def onWrite(path: os.Path): Unit = {
    if (mill.api.FilesystemCheckerEnabled.value) {
      sys.error(s"Writing to ${path.relativeTo(workspace)} not allowed during resolution phase")
    }
  }

  def withResolveChecker[T](f: () => T): T = {
    os.checker.withValue(this) {
      f()
    }
  }

}
