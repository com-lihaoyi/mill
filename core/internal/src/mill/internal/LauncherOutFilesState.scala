package mill.internal

import mill.constants.DaemonFiles

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private[mill] class LauncherOutFilesState {
  private val publishedPathLocks = new ConcurrentHashMap[String, AnyRef]()
  private val runIdCounter = AtomicLong(0L)
  private val tmpNameCounter = AtomicLong(0L)
  // The PR design allows a MillNoDaemonMain process to share `out/` with a
  // (formerly running) MillDaemonMain. Mix in the PID and a per-process run
  // counter so run directories and launcher record files cannot collide across
  // processes or quick successive runs.
  private val pid: Long = ProcessHandle.current().pid()

  def publishedPathLockFor(normalizedAbsolutePath: String): AnyRef =
    publishedPathLocks.computeIfAbsent(normalizedAbsolutePath, _ => new Object)

  def nextRunId(): String = {
    val now = Instant.now()
    val timestamp = LauncherOutFilesState.runIdTimestampFormatter.format(now)
    s"run-${runIdCounter.getAndIncrement()}_${timestamp}_pid-${pid}"
  }

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherOutFilesState {
  val runRootDirName = DaemonFiles.millRun
  private[mill] val runIdTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
}
