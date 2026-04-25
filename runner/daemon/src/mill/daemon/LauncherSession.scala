package mill.daemon

import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.internal.{LauncherLockingImpl, LauncherOutFilesImpl}

private[daemon] trait LauncherSession extends AutoCloseable {
  def workspaceLocking: LauncherLocking
  def runArtifacts: LauncherOutFiles
}

private[daemon] object LauncherSession {

  object Noop extends LauncherSession {
    val workspaceLocking: LauncherLocking = LauncherLocking.Noop
    val runArtifacts: LauncherOutFiles = LauncherOutFiles.Noop
    override def close(): Unit = ()
  }

  final class Daemon(
      val workspaceLocking: LauncherLockingImpl,
      val runArtifacts: LauncherOutFilesImpl
  ) extends LauncherSession {
    override def close(): Unit = {
      try workspaceLocking.close()
      catch { case _: Throwable => () }
      try runArtifacts.close()
      catch { case _: Throwable => () }
    }
  }
}
