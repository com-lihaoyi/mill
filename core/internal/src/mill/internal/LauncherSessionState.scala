package mill.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Workspace-scoped state shared across concurrent launcher sessions: one
 * instance per daemon (or per process, for `--no-daemon`), passed into each new
 * session. Carries the workspace-wide [[WriterPreferringRwLock]]s plus the
 * session-identity counters (run id, tmp suffix) used by per-run artifact
 * routing. Counters live here rather than separately so a single object
 * captures all daemon-wide per-launcher coordination state.
 *
 * Meta-build locks are keyed by depth so that a launcher holding a downgraded
 * read lease at a deeper depth does not block itself when acquiring a write
 * lease at a shallower depth on the same thread; depths are independent
 * build-artifact scopes. Task locks are keyed by normalized absolute path.
 */
private[mill] final class LauncherSessionState {
  private val metaBuildLocks = new ConcurrentHashMap[Int, WriterPreferringRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, WriterPreferringRwLock]()
  private val bootstrapLockInstance = new WriterPreferringRwLock("bootstrap-module")
  private val runIdCounter = new AtomicLong(0L)
  private val tmpNameCounter = new AtomicLong(0L)

  def metaBuildLockFor(depth: Int): WriterPreferringRwLock =
    metaBuildLocks.computeIfAbsent(depth, d => new WriterPreferringRwLock(s"meta-build-$d"))

  def taskLockFor(normalizedAbsolutePath: String): WriterPreferringRwLock =
    taskLocks.computeIfAbsent(normalizedAbsolutePath, p => new WriterPreferringRwLock(p))

  def bootstrapLock: WriterPreferringRwLock = bootstrapLockInstance

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherSessionState {
  val runRootDirName = "mill-run"
}
