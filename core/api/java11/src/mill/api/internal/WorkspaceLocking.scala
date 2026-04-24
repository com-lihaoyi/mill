package mill.api.internal

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

private[mill] object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  trait Lease extends AutoCloseable

  trait DowngradableLease extends Lease {
    def downgradeToRead(): Unit
  }

  trait Manager extends AutoCloseable {
    def runId: String
    def consoleTail: os.Path

    /** Returns a per-run path for well-known out/ artifacts in daemon mode. */
    def artifactPath(default: os.Path): os.Path = default

    /** Publishes this run's well-known artifacts as the latest visible ones under `out/`. */
    def publishArtifacts(): Unit = ()

    def withSelectiveExecutionLock[T](@scala.annotation.unused path: os.Path)(t: => T): T = t
    def metaBuildLock(kind: LockKind): DowngradableLease
    def taskLock(path: os.Path, kind: LockKind): DowngradableLease
  }

  object NoopManager extends Manager {
    override def runId: String = "noop"
    override def consoleTail: os.Path = os.pwd / "out" / "mill-console-tail"
    override def metaBuildLock(lockKind: LockKind): DowngradableLease = new DowngradableLease {
      override def downgradeToRead(): Unit = ()
      override def close(): Unit = ()
    }
    override def taskLock(path: os.Path, kind: LockKind): DowngradableLease = metaBuildLock(kind)
    override def close(): Unit = ()
  }

  final class InProcessManager(
      out: os.Path,
      daemonDir: os.Path,
      activeCommandMessage: String,
      launcherPid: Long,
      waitingErr: PrintStream,
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean
  ) extends Manager {
    private val runInfo = WorkspaceRunArtifacts.RunInfo(launcherPid, activeCommandMessage)
    override val runId: String = WorkspaceRunArtifacts.nextRunId()
    private val closed = new AtomicBoolean(false)
    private val activeLeases = scala.collection.mutable.Set.empty[LeaseWrapper]
    private val artifacts = new WorkspaceRunArtifacts.RunArtifacts(runId, out, daemonDir, runInfo)
    private val locks = WorkpaceLockingUtils.locksFor(out)

    override val consoleTail: os.Path = artifacts.consoleTail

    artifacts.cleanupOldRunDirs()

    private def ensureOpen(): Unit =
      if (closed.get()) throw new IllegalStateException(s"Lock manager $runId is closed")

    override def artifactPath(default: os.Path): os.Path =
      artifacts.artifactPath(default)

    override def publishArtifacts(): Unit = {
      ensureOpen()
      artifacts.publish()
    }

    override def withSelectiveExecutionLock[T](@scala.annotation.unused path: os.Path)(t: => T): T =
      if (noBuildLock) {
        publishArtifacts()
        t
      } else locks.withSelectiveExecutionLock(waitingErr, noWaitForBuildLock)(t)

    override def metaBuildLock(kind: LockKind): DowngradableLease = {
      ensureOpen()
      if (noBuildLock) {
        publishArtifacts()
        NoopManager.metaBuildLock(kind)
      } else {
        acquireManagedLease(locks.metaBuildLock(kind, waitingErr, noWaitForBuildLock))
      }
    }

    override def taskLock(path: os.Path, kind: LockKind): DowngradableLease = {
      ensureOpen()
      if (noBuildLock) {
        publishArtifacts()
        NoopManager.metaBuildLock(kind)
      } else {
        acquireManagedLease(locks.taskLock(path, kind, waitingErr, noWaitForBuildLock))
      }
    }

    private def acquireManagedLease(
        underlying: DowngradableLease
    ): DowngradableLease = {
      val lease = new LeaseWrapper(underlying)
      try {
        activeLeases.synchronized {
          ensureOpen()
          activeLeases += lease
          artifacts.publish()
        }
        lease
      } catch {
        case e: Throwable =>
          try lease.close()
          catch { case _: Throwable => }
          throw e
      }
    }

    override def close(): Unit = {
      var shouldClose = false
      val leases = activeLeases.synchronized {
        shouldClose = closed.compareAndSet(false, true)
        if (shouldClose) activeLeases.toSeq else Nil
      }
      if (shouldClose) {
        leases.foreach(lease =>
          try lease.close()
          catch { case _: Throwable => }
        )
        artifacts.close()
      }
    }

    private final class LeaseWrapper(
        underlying: DowngradableLease
    ) extends DowngradableLease {
      private val closed = new AtomicBoolean(false)

      override def downgradeToRead(): Unit =
        if (!closed.get()) underlying.downgradeToRead()

      override def close(): Unit =
        if (closed.compareAndSet(false, true)) {
          underlying.close()
          activeLeases.synchronized(activeLeases -= this)
        }
    }
  }
}
