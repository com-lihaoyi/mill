package mill.exec

import mill.api.Task
import mill.api.daemon.internal.LauncherLocking
import mill.internal.LockUpgrade

private[exec] final class TaskLockCoordinator(
    labelled: Task.Named[?],
    taskLockPath: java.nio.file.Path,
    workspaceLocking: LauncherLocking,
    waitReporter: LauncherLocking.WaitReporter,
    leaseTracker: Execution.LeaseTracker,
    executionContext: mill.api.TaskCtx.Fork.Api
) {
  private val label = labelled.ctx.segments.render
  private val taskLockKey = taskLockPath.toAbsolutePath.normalize().toString

  // #7132: waits and remote-cache I/O run on the bounded execution pool. Use
  // `blocking` for paths that can park so the pool can spawn a compensating worker.
  def blockingOnPool[T](t: => T): T = executionContext.blocking(t)

  // Any successful task-lock acquisition must validate reads that were
  // dropped during an earlier contended wait before evaluation continues.
  // This path may block, so only call it when we are not holding a task
  // Write lease. Guard on `hasDropped` to avoid no-op fast-path pool churn.
  private def reacquireDroppedReads(): Unit =
    if (leaseTracker.hasDropped) blockingOnPool {
      leaseTracker.reacquireDropped(workspaceLocking, waitReporter)
    }

  // The write-upgrade callback and Named write body run while holding
  // this task's Write lock, so dropped reads must be reacquired only via
  // non-blocking try-locks. A writer that performs a blocking task-lock
  // acquire can wait on another writer while retaining its own Write
  // lease, reintroducing the retained-read/write cycle this logic avoids.
  private def tryReacquireDroppedReads(): Unit =
    leaseTracker.reacquireDropped(workspaceLocking, waitReporter, block = false)

  private def validateDroppedReads(lease: LauncherLocking.Lease): LauncherLocking.Lease =
    try {
      reacquireDroppedReads()
      lease
    } catch {
      case t: Throwable =>
        lease.close()
        throw t
    }

  // Input tasks (Task.Input/Source/Sources) are deterministic from
  // filesystem/env, downstream consumes the in-memory Val (not
  // `dest/`), and concurrent `dest/meta.json` writes are byte-identical,
  // so per-task locking is unnecessary and just serializes peers.
  private def acquireReadTaskLock(): LauncherLocking.Lease = {
    def blockingAcquire(): LauncherLocking.Lease =
      blockingOnPool {
        workspaceLocking.taskLock(
          taskLockPath,
          label,
          LauncherLocking.LockKind.Read,
          waitReporter
        )
      }

    if (labelled.isInputTask)
      LauncherLocking.Noop.taskLock(
        taskLockPath,
        label,
        LauncherLocking.LockKind.Read,
        waitReporter
      )
    else {
      workspaceLocking.tryTaskReadLock(taskLockPath, label) match {
        case Right(lease) => validateDroppedReads(lease)
        case Left(_) =>
          leaseTracker.releaseHigherThan(taskLockKey)
          validateDroppedReads(blockingAcquire())
      }
    }
  }

  // Try-Write + bounded await for `LockUpgrade.readThenWrite`'s
  // retryable loop; mirrors `acquireReadTaskLock`'s input-task skip.
  private def tryWriteTaskLock(): Either[LauncherLocking.Contention, LauncherLocking.Lease] =
    if (labelled.isInputTask)
      LauncherLocking.Noop.tryTaskWriteLock(taskLockPath, label)
    else workspaceLocking.tryTaskWriteLock(taskLockPath, label)

  private def awaitTaskLockChange(timeoutMs: Long): Unit =
    if (!labelled.isInputTask) {
      leaseTracker.releaseHigherThan(taskLockKey)
      blockingOnPool {
        workspaceLocking.awaitTaskStateChange(taskLockPath, label, timeoutMs)
      }
    }

  def readThenWrite[T](
      readBody: LockUpgrade.Scope => LockUpgrade.Decision[T]
  )(
      writeBody: LockUpgrade.Scope => T
  ): T =
    LockUpgrade.readThenWrite(
      acquireRead = acquireReadTaskLock(),
      tryAcquireWrite = () => tryWriteTaskLock(),
      awaitStateChange = awaitTaskLockChange,
      waitReporter = waitReporter,
      afterAcquire = tryReacquireDroppedReads
    )(readBody)(writeBody)

  def retainRead(scope: LockUpgrade.Scope): Unit =
    leaseTracker.retain(
      labelled,
      taskLockPath,
      label,
      scope.retain(),
      workspaceLocking.taskVersion(taskLockPath)
    )

  def retainDowngraded(scope: LockUpgrade.Scope, version: Long): Unit =
    leaseTracker.retain(labelled, taskLockPath, label, scope.downgradeAndRetain(), version)

  def currentVersion: Long = workspaceLocking.taskVersion(taskLockPath)

  def markTaskWritten(): Long = workspaceLocking.markTaskWritten(taskLockPath)

  def withActiveConsumers[T](body: => T): T =
    leaseTracker.withActiveConsumers(labelled, () => tryReacquireDroppedReads())(body)
}
