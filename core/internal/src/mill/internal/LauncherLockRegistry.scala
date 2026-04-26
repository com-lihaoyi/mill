package mill.internal

import java.util.concurrent.ConcurrentHashMap

private[mill] final class LauncherLockRegistry {
  private val metaBuildLocks = new ConcurrentHashMap[Int, CrossThreadRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, CrossThreadRwLock]()

  def metaBuildLockFor(depth: Int): CrossThreadRwLock =
    metaBuildLocks.computeIfAbsent(
      depth,
      d => new CrossThreadRwLock(label = s"meta-build-$d")
    )

  def taskLockFor(
      normalizedAbsolutePath: String,
      displayLabel: String
  ): CrossThreadRwLock =
    taskLocks.computeIfAbsent(
      normalizedAbsolutePath,
      p => new CrossThreadRwLock(label = p, displayLabel = displayLabel)
    )
}
