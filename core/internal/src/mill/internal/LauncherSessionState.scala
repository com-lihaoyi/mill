package mill.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private[mill] final class LauncherSessionState {
  private val metaBuildLocks = new ConcurrentHashMap[Int, WriterPreferringRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, WriterPreferringRwLock]()
  private val artifactLocks = new ConcurrentHashMap[String, AnyRef]()
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

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherSessionState {
  val runRootDirName = "mill-run"
}
