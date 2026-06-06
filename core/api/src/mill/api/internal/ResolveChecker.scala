package mill.api.internal

import mill.api.PathRef

private[mill] class ResolveChecker(workspace: os.Path) extends os.Checker {
  private def displayPath(path: os.Path): os.RelPath =
    PathRef.toResolvedOsPathAnchored(path, workspace).relativeTo(workspace)

  def onRead(path: os.ReadablePath): Unit = {
    path match {
      case path: os.Path if mill.api.FilesystemCheckerEnabled.value =>
        sys.error(
          s"Reading from ${displayPath(path)} not allowed during resolution phase.\n" +
            "You may only read from files during resolution within a `BuildCtx.watchValue` block."
        )
      case _ =>
    }
  }

  def onWrite(path: os.Path): Unit = {
    if (mill.api.FilesystemCheckerEnabled.value) {
      sys.error(s"Writing to ${displayPath(path)} not allowed during resolution phase")
    }
  }

  def withResolveChecker[T](f: () => T): T = {
    os.checker.withValue(this) {
      f()
    }
  }

}
