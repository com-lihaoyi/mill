package mill.api.internal

import mill.api.internal.WorkspaceLocking.{DowngradableLease, LockKind}
import mill.constants.DaemonFiles

import java.util.concurrent.ConcurrentHashMap

private object WorkpaceLockingUtils {

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

  /**
   * A small fair read/write lock with lease-based ownership rather than thread-based ownership.
   *
   * We cannot use `java.util.concurrent.locks.ReentrantReadWriteLock` here because Mill acquires
   * task/meta-build locks on worker threads, may downgrade them to read locks, and then retains
   * those read leases until later cleanup on a different thread. The standard library RW locks
   * tie lock ownership to the acquiring thread, while this lock ties ownership to the returned
   * lease object instead.
   */
  private final class FairRwLock(label: String) {
    private val monitor = new Object
    private var readerCount = 0
    private var writerActive = false
    private var waitingWriters = 0

    private def canAcquireRead: Boolean = !writerActive && waitingWriters == 0
    private def canAcquireWrite: Boolean = !writerActive && readerCount == 0

    private def waitingMessage: String =
      s"Another Mill command in the current daemon is using resource '$label'"

    def acquire(
        kind: LockKind,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): DowngradableLease = acquire0(
      isWrite = kind == LockKind.Write,
      waitingErr = waitingErr,
      noWait = noWait
    )

    private def acquire0(
        isWrite: Boolean,
        waitingErr: java.io.PrintStream,
        noWait: Boolean
    ): DowngradableLease = {
      val shouldWait = monitor.synchronized {
        val available = if (isWrite) canAcquireWrite else canAcquireRead
        if (available) false
        else if (noWait) throw new Exception(s"${waitingMessage} and --no-wait was set, failing")
        else {
          if (isWrite) waitingWriters += 1
          true
        }
      }

      if (shouldWait) {
        waitingErr.println(
          s"$waitingMessage, waiting for it to be done... " +
            s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
        )
      }

      monitor.synchronized {
        val waitingWriterRegistered = shouldWait && isWrite
        try {
          while ({
            val available = if (isWrite) canAcquireWrite else canAcquireRead
            !available
          }) monitor.wait()
        } catch {
          case e: Throwable =>
            if (waitingWriterRegistered) {
              waitingWriters -= 1
              monitor.notifyAll()
            }
            throw e
        }
        if (waitingWriterRegistered) waitingWriters -= 1

        if (isWrite) writerActive = true
        else readerCount += 1

        new DowngradableLease {
          private var closed = false
          private var readMode = !isWrite

          override def downgradeToRead(): Unit = monitor.synchronized {
            if (!closed && !readMode) {
              writerActive = false
              readerCount += 1
              readMode = true
              monitor.notifyAll()
            }
          }

          override def close(): Unit = monitor.synchronized {
            if (!closed) {
              if (readMode) readerCount -= 1
              else writerActive = false
              closed = true
              monitor.notifyAll()
            }
          }
        }
      }
    }
  }

  private val registries = new ConcurrentHashMap[String, LockRegistry]()

  def locksFor(out: os.Path): LockRegistry =
    registries.computeIfAbsent(out.toString, _ => new LockRegistry)
}
