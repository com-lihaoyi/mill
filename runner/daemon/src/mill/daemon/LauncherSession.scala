package mill.daemon

import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}

private[daemon] trait LauncherSession extends AutoCloseable {
  def workspaceLocking: LauncherLocking
  def runArtifacts: LauncherOutFiles
}

private[daemon] object LauncherSession {
  def standalone(fileLockLease: SharedOutLockManager.Lease, out: os.Path): LauncherSession =
    new Active(fileLockLease, LauncherLocking.Noop, LauncherOutFiles.noop(out.toNIO))

  final class Active(
      fileLockLease: SharedOutLockManager.Lease,
      val workspaceLocking: LauncherLocking,
      val runArtifacts: LauncherOutFiles
  ) extends LauncherSession {
    override def close(): Unit = {
      try workspaceLocking.close()
      catch { case _: Throwable => () }
      try runArtifacts.close()
      catch { case _: Throwable => () }
      try fileLockLease.close()
      catch { case _: Throwable => () }
    }
  }
}
