package mill.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps of locks that are shared across launchers to allow them to coordinate work
 * evaluating the meta-build or individual tasks without conflicting with each other
 */
private[mill] class LauncherLockRegistry {
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
      _ => new CrossThreadRwLock(label = displayLabel)
    )
}
