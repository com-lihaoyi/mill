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

  // Single daemon-wide lock guarding "globally exclusive" command execution. Read leases
  // are taken by every normal task batch (and by plain `exclusive` commands) so they share
  // freely; write leases are taken by globally-exclusive command batches
  // (`Task.Command(globalExclusive = true)`, e.g. `clean`) so they run alone across all
  // launchers.
  val exclusiveLock: CrossThreadRwLock =
    new CrossThreadRwLock(
      label = "exclusive",
      showLabelInMessage = true,
      syntheticPrefix = Seq("exclusive-lock")
    )

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
      _ => new CrossThreadRwLock(label = displayLabel, showLabelInMessage = false)
    )
}

private[mill] object LauncherLockRegistry {

  private val makeMetaBuildLock: java.util.function.Function[Int, CrossThreadRwLock] =
    (depth: Int) => {
      val prefix = metaBuildPromptPrefix(depth)
      new CrossThreadRwLock(
        label = prefix.headOption.getOrElse(""),
        showLabelInMessage = false,
        syntheticPrefix = prefix
      )
    }

  /**
   * Mirrors `MillBuildBootstrap.bootLogPrefix(depth)` so wait messages and
   * synthetic prompt-line keys reference each meta-build by the name the
   * user already sees in the multi-line prompt:
   *   - depth 0: `Nil`                         (user-level project, no prefix)
   *   - depth 1: `Seq("build.mill")`
   *   - depth 2: `Seq("mill-build/build.mill")`
   *   - depth N: `(N-1)` `mill-build` segments + `build.mill`
   *
   * The exact build-file name (`build.mill` vs `build.mill.yaml`) varies
   * per project but is fixed for one daemon — using the canonical
   * `build.mill` here keeps the label readable without needing to thread
   * the actual filename through the lock registry.
   */
  def metaBuildPromptPrefix(depth: Int): Seq[String] =
    if (depth == 0) Nil
    else Seq((Seq.fill(depth - 1)(OutFiles.millBuild) :+ "build.mill").mkString("/"))
}
