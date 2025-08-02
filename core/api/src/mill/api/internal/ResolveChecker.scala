package mill.api.internal

private[mill] class ResolveChecker(workspace: os.Path) extends os.Checker {
  def onRead(path: os.ReadablePath): Unit = {
    path match {
      case path: os.Path if mill.api.FilesystemCheckerEnabled.value =>
        sys.error(
          s"Reading from ${path.relativeTo(workspace)} not allowed during resolution phase.\n" +
            "You may only read from files during resolution within a `BuildCtx.watchValue` block."
        )
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
