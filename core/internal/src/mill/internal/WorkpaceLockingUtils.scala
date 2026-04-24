package mill.internal

import mill.api.internal.WorkspaceLocking.{DowngradableLease, LockKind}

import java.util.concurrent.ConcurrentHashMap

private[mill] object WorkpaceLockingUtils {

  final class LockRegistry {
    private val metaBuildLock0 = new FairRwLock("meta-build")
    private val selectiveExecutionLock0 = new FairRwLock("selective-execution")
    private val taskLocks = new ConcurrentHashMap[String, FairRwLock]()

    def metaBuildLock(
        kind: LockKind,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): DowngradableLease = metaBuildLock0.acquire(kind, waitingErr, noWait)

    def taskLock(
        path: os.Path,
        kind: LockKind,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): DowngradableLease = {
      val normalized = path.toNIO.toAbsolutePath.normalize().toString
      taskLocks.computeIfAbsent(normalized, _ => new FairRwLock(normalized))
        .acquire(kind, waitingErr, noWait)
    }

    def withSelectiveExecutionLock[T](
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    )(t: => T): T = {
      val lease = selectiveExecutionLock0.acquire(LockKind.Write, waitingErr, noWait)
      try t
      finally lease.close()
    }
  }

  private val registries = new ConcurrentHashMap[String, LockRegistry]()

  def locksFor(out: os.Path): LockRegistry =
    registries.computeIfAbsent(out.toString, _ => new LockRegistry)
}
