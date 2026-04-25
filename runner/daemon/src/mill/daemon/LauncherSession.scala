package mill.daemon

import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.internal.{LauncherLockingImpl, LauncherOutFilesImpl}

/**
 * Per-launcher resources bundled into a single AutoCloseable: the in-memory
 * [[LauncherLocking]] handle for intra-daemon per-task / per-meta-build
 * leases, plus the on-disk [[LauncherOutFiles]] handle for per-run scratch
 * and `out/mill-*` symlink publishing.
 *
 * Always non-null on the call path — [[Noop]] handles the no-daemon case
 * uniformly so callers don't have to branch on `Option[…]`. Closing tears
 * the locking handle down first (releasing any retained intra-daemon
 * leases), then the out-files handle (which reacquires the cross-process
 * file lock briefly to publish its close-time cleanup).
 */
private[daemon] trait LauncherSession extends AutoCloseable {
  def workspaceLocking: LauncherLocking
  def runArtifacts: LauncherOutFiles
}

private[daemon] object LauncherSession {

  /** No-daemon mode: no intra-daemon coordination, no per-run scratch dir. */
  object Noop extends LauncherSession {
    val workspaceLocking: LauncherLocking = LauncherLocking.Noop
    val runArtifacts: LauncherOutFiles = LauncherOutFiles.Noop
    override def close(): Unit = ()
  }

  /**
   * Daemon mode: a real per-launcher locking handle + a real per-run
   * out-files handle. Constructed by the caller inside the `withLocking`
   * block (i.e. while the cross-process out/ file lock is held), so the
   * out-files setup runs under that lock.
   */
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
