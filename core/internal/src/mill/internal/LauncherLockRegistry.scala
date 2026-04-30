package mill.internal

import mill.constants.OutFiles

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps of locks that are shared across launchers to allow them to coordinate work
 * evaluating the meta-build or individual tasks without conflicting with each other
 */
private[mill] class LauncherLockRegistry {
  private val metaBuildLocks = new ConcurrentHashMap[Int, CrossThreadRwLock]()
  private val taskLocks = new ConcurrentHashMap[String, CrossThreadRwLock]()

  // Single daemon-wide lock guarding "exclusive" command execution. Read leases are
  // taken by every normal task batch so they share freely; write leases are taken
  // by exclusive command batches (`Task.Command(exclusive = true)`, e.g. `clean`)
  // so they run alone across all launchers.
  val exclusiveLock: CrossThreadRwLock = new CrossThreadRwLock(label = "exclusive")

  def metaBuildLockFor(depth: Int): CrossThreadRwLock =
    metaBuildLocks.computeIfAbsent(
      depth,
      LauncherLockRegistry.makeMetaBuildLock
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

private[mill] object LauncherLockRegistry {
  /** Mirror the meta-build prompt-line prefix used by `MillBuildBootstrap`'s
    * `bootLogPrefix`, so wait messages reference the build file by the same
    * name the user already sees in the multi-line prompt:
    *   - depth 0: `build.mill`         (the user-level project)
    *   - depth 1: `mill-build/build.mill`
    *   - depth N: `mill-build/.../mill-build/build.mill` (N segments)
    *
    * The exact build-file name (`build.mill` vs `build.mill.yaml`) varies
    * per project but is fixed for one daemon — using the canonical
    * `build.mill` here keeps the lock label readable without needing to
    * thread the actual filename through the lock registry. */
  private val makeMetaBuildLock: java.util.function.Function[Int, CrossThreadRwLock] =
    (depth: Int) => {
      val label =
        (Seq.fill(depth)(OutFiles.millBuild) :+ "build.mill").mkString("/")
      new CrossThreadRwLock(label = label)
    }
}
