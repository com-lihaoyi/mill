package mill.internal

import mill.constants.DaemonFiles

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private[mill] class LauncherOutFilesState {
  private val publishedPathLocks = new ConcurrentHashMap[String, AnyRef]()
  private val runIdCounter = AtomicLong(0L)
  private val tmpNameCounter = AtomicLong(0L)
  // The PR design allows a MillNoDaemonMain process to share `out/` with a
  // (formerly running) MillDaemonMain. Two processes starting in the same
  // millisecond would otherwise produce identical runIds; mix in the PID so
  // run directories and launcher record files cannot collide across processes.
  private val pid: Long = ProcessHandle.current().pid()

  def publishedPathLockFor(normalizedAbsolutePath: String): AnyRef =
    publishedPathLocks.computeIfAbsent(normalizedAbsolutePath, _ => new Object)

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-$pid-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherOutFilesState {
  val runRootDirName = DaemonFiles.millRun
}
