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
  // Per-link intra-process monitors used by LauncherOutFilesImpl to serialize
  // its own cleanup-vs-publish on the same out/mill-* symlink. These are
  // separate from the cross-process out/ file lock (held via
  // SharedOutLockManager) — they prevent two threads of the same daemon from
  // racing on a single link's test-then-act sequence even while the daemon
  // holds the file lock against external processes.
  private val artifactLocks = new ConcurrentHashMap[String, AnyRef]()
  private val bootstrapLockInstance =
    new WriterPreferringRwLock(label = "bootstrap-module", displayLabel = "bootstrap-module")
  private val runIdCounter = new AtomicLong(0L)
  private val tmpNameCounter = new AtomicLong(0L)

  def metaBuildLockFor(depth: Int): WriterPreferringRwLock =
    metaBuildLocks.computeIfAbsent(
      depth,
      d => new WriterPreferringRwLock(label = s"meta-build-$d")
    )

  def taskLockFor(
      normalizedAbsolutePath: String,
      displayLabel: String
  ): WriterPreferringRwLock =
    taskLocks.computeIfAbsent(
      normalizedAbsolutePath,
      p => new WriterPreferringRwLock(label = p, displayLabel = displayLabel)
    )

  def artifactLockFor(normalizedAbsolutePath: String): AnyRef =
    artifactLocks.computeIfAbsent(normalizedAbsolutePath, _ => new Object)

  def bootstrapLock: WriterPreferringRwLock = bootstrapLockInstance

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherSessionState {
  val runRootDirName = "mill-run"
}
