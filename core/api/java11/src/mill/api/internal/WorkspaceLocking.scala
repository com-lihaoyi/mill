package mill.api.internal

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

private[mill] object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  trait Lease extends AutoCloseable

  trait DowngradableLease extends Lease {
    def kind: LockKind
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
      override def kind: LockKind = lockKind
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
    private val owner = WorkpaceLockingUtils.LockOwner("", launcherPid, activeCommandMessage)
    override val runId: String = WorkpaceLockingUtils.nextRunId()
    private val closed = new AtomicBoolean(false)
    private val activeLeases = scala.collection.mutable.Set.empty[ManagedLease]
    private val artifacts = new WorkpaceLockingUtils.RunArtifacts(runId, out, daemonDir, owner)
    private val locks = WorkpaceLockingUtils.locksFor(out)
    private val runOwner = owner.copy(runId = runId)

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

    override def withSelectiveExecutionLock[T](path: os.Path)(t: => T): T = {
      val lease = locks.acquire(
        WorkpaceLockingUtils.LockId.selectiveExecution(path),
        LockKind.Write,
        runOwner,
        waitingErr,
        noWaitForBuildLock
      )
      try t
      finally lease.close()
    }

    override def metaBuildLock(kind: LockKind): DowngradableLease =
      acquireManagedLease(WorkpaceLockingUtils.LockId.MetaBuild, kind)

    override def taskLock(path: os.Path, kind: LockKind): DowngradableLease =
      acquireManagedLease(WorkpaceLockingUtils.LockId.task(path), kind)

    private def acquireManagedLease(
        lockId: WorkpaceLockingUtils.LockId,
        kind: LockKind
    ): DowngradableLease = {
      ensureOpen()
      if (noBuildLock) {
        publishArtifacts()
        NoopManager.metaBuildLock(kind)
      } else {
        val underlying = locks.acquire(lockId, kind, runOwner, waitingErr, noWaitForBuildLock)
        val lease = new ManagedLease(underlying)
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

    private final class ManagedLease(
        underlying: WorkpaceLockingUtils.ManagedLease
    ) extends DowngradableLease {
      private val closed = new AtomicBoolean(false)

      override def kind: LockKind = underlying.kind

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
