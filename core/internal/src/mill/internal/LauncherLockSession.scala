package mill.internal

import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.api.daemon.internal.LauncherLocking.LockKind

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One run's concrete handle into workspace locking and artifact routing.
 * Implements both [[LauncherLocking]] and [[LauncherOutFiles]] over the
 * same underlying state: callers take whichever aspect they need and the
 * implementation keeps them coordinated internally (e.g. closing the session
 * releases any outstanding leases and deactivates the artifact routing).
 */
private[mill] final class LauncherLockSession(
    out: os.Path,
    daemonDir: os.Path,
    activeCommandMessage: String,
    launcherPid: Long,
    waitingErr: PrintStream,
    noBuildLock: Boolean,
    noWaitForBuildLock: Boolean
) extends LauncherLocking with LauncherOutFiles {
  private val runInfo = RunArtifactStore.RunInfo(launcherPid, activeCommandMessage)
  override val runId: String = RunArtifactStore.nextRunId()
  private val closed = new AtomicBoolean(false)
  private val activeLeases = scala.collection.mutable.Set.empty[LeaseWrapper]
  private val artifacts = new RunArtifactStore.RunArtifacts(runId, out, daemonDir, runInfo)
  private val locks = LauncherLockSession.locksFor(out)

  override val consoleTail: java.nio.file.Path = artifacts.consoleTail.toNIO

  artifacts.cleanupOldRunDirs()

  private def ensureOpen(): Unit =
    if (closed.get()) throw new IllegalStateException(s"Lock session $runId is closed")

  override def artifactPath(default: java.nio.file.Path): java.nio.file.Path =
    artifacts.artifactPath(os.Path(default)).toNIO

  override def publishArtifacts(): Unit = {
    ensureOpen()
    artifacts.publish()
  }

  override def withSelectiveExecutionLock[T](
      @scala.annotation.unused path: java.nio.file.Path
  )(t: => T): T =
    if (noBuildLock) t
    else {
      val lease =
        locks.selectiveExecutionLock.acquire(LockKind.Write, waitingErr, noWaitForBuildLock)
      try t
      finally lease.close()
    }

  override def metaBuildLock(kind: LauncherLocking.LockKind): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.metaBuildLock(kind)
    else acquireManagedLease(locks.metaBuildLock.acquire(kind, waitingErr, noWaitForBuildLock))
  }

  override def taskLock(
      path: java.nio.file.Path,
      kind: LauncherLocking.LockKind
  ): LauncherLocking.Lease = {
    ensureOpen()
    if (noBuildLock) LauncherLocking.Noop.taskLock(path, kind)
    else {
      val normalized = path.toAbsolutePath.normalize().toString
      val lock = locks.taskLocks.computeIfAbsent(normalized, _ => new FairRwLock(normalized))
      acquireManagedLease(lock.acquire(kind, waitingErr, noWaitForBuildLock))
    }
  }

  private def acquireManagedLease(
      underlying: LauncherLocking.Lease
  ): LauncherLocking.Lease = {
    val lease = new LeaseWrapper(underlying)
    try {
      activeLeases.synchronized {
        ensureOpen()
        activeLeases += lease
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
      underlying: LauncherLocking.Lease
  ) extends LauncherLocking.Lease {
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

private[mill] object LauncherLockSession {
  // Locks are shared across all WorkspaceLockSessions for the same `out` path so
  // concurrent launchers in the same daemon serialize correctly. Keyed by `out`
  // rather than daemon-scoped because daemon lifecycle threading would require
  // plumbing a shared instance through every caller.
  private final class Locks {
    val metaBuildLock = new FairRwLock("meta-build")
    val selectiveExecutionLock = new FairRwLock("selective-execution")
    val taskLocks = new ConcurrentHashMap[String, FairRwLock]()
  }

  private val locksByOut = new ConcurrentHashMap[String, Locks]()

  private def locksFor(out: os.Path): Locks =
    locksByOut.computeIfAbsent(out.toString, _ => new Locks)
}
