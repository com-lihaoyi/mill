package mill.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private[mill] final class LauncherArtifactState {
  private val artifactLocks = new ConcurrentHashMap[String, AnyRef]()
  private val runIdCounter = new AtomicLong(0L)
  private val tmpNameCounter = new AtomicLong(0L)

  def artifactLockFor(normalizedAbsolutePath: String): AnyRef =
    artifactLocks.computeIfAbsent(normalizedAbsolutePath, _ => new Object)

  def nextRunId(): String =
    s"${System.currentTimeMillis()}-${runIdCounter.getAndIncrement()}"

  def nextTmpSuffix(): Long = tmpNameCounter.getAndIncrement()
}

private[mill] object LauncherArtifactState {
  val runRootDirName = "mill-run"
}
