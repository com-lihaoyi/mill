package mill.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Workspace-scoped state shared across concurrent [[LauncherLockingImpl]]
 * sessions: one instance per daemon (or per process, for `--no-daemon`), passed
 * into each new session. Makes the sharing explicit at the call sites rather
 * than implicit via process-wide maps.
 *
 * Meta-build locks are keyed by depth so that a launcher holding a downgraded
 * read lease at a deeper depth does not block itself when acquiring a write
 * lease at a shallower depth on the same thread; depths are independent
 * build-artifact scopes. Task locks are keyed by normalized absolute path.
 */
private[mill] final class LauncherLockingState {
  private val metaBuildLocks = new ConcurrentHashMap[Int, FairRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, FairRwLock]()
  private val runIdCounter = new AtomicLong(0L)
  private val tmpNameCounter = new AtomicLong(0L)

  def metaBuildLockFor(depth: Int): FairRwLock =
    metaBuildLocks.computeIfAbsent(depth, d => new FairRwLock(s"meta-build-$d"))

  def taskLockFor(normalizedAbsolutePath: String): FairRwLock =
    taskLocks.computeIfAbsent(normalizedAbsolutePath, p => new FairRwLock(p))

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherLockingState {
  val runRootDirName = "mill-run"
}
