package mill.internal

import java.util.concurrent.ConcurrentHashMap

private[mill] final class LauncherLockRegistry {
  private val metaBuildLocks = new ConcurrentHashMap[Int, WriterPreferringRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, WriterPreferringRwLock]()

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
}
