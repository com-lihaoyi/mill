package mill.internal

import mill.constants.OutFiles

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps of locks that are shared across launchers to allow them to coordinate work
 * evaluating the meta-build or individual tasks without conflicting with each other
 */
private[mill] class LauncherLockRegistry {
  import LauncherLockRegistry.{LockCell, LockKey}

  private val locks = new ConcurrentHashMap[LockKey, LockCell]()
  private val taskWriteVersion = new AtomicLong(0L)

  // Single daemon-wide lock guarding "globally exclusive" command execution. Read leases
  // are taken by every normal task batch (and by plain `exclusive` commands) so they share
  // freely; write leases are taken by globally-exclusive command batches
  // (`Task.Command(globalExclusive = true)`, e.g. `clean`) so they run alone across all
  // launchers.
  val exclusiveLock: CrossThreadRwLock =
    lockFor(
      LockKey.Exclusive,
      new CrossThreadRwLock(
        label = "exclusive",
        showLabelInMessage = true,
        syntheticPrefix = Seq("exclusive-lock")
      )
    )

  def metaBuildLockFor(depth: Int): CrossThreadRwLock = {
    val prefix = LauncherLockRegistry.metaBuildPromptPrefix(depth)
    lockFor(
      LockKey.MetaBuild(depth),
      new CrossThreadRwLock(
        label = prefix.headOption.getOrElse(""),
        showLabelInMessage = false,
        syntheticPrefix = prefix
      )
    )
  }

  def taskLockFor(
      normalizedAbsolutePath: String,
      displayLabel: String
  ): CrossThreadRwLock =
    lockFor(
      LockKey.Task(normalizedAbsolutePath),
      CrossThreadRwLock(label = displayLabel, showLabelInMessage = false)
    )

  def taskVersion(normalizedAbsolutePath: String): Long =
    Option(locks.get(LockKey.Task(normalizedAbsolutePath))).fold(0L)(_.version.get())

  def markTaskWritten(normalizedAbsolutePath: String): Long = {
    // Bump even when a rewrite produces byte-identical files. Dropped-read
    // validation is deliberately conservative: if another launcher had a
    // chance to rewrite this output, restart rather than comparing directory
    // contents while task locks are in flux.
    val version = taskWriteVersion.incrementAndGet()
    cellFor(LockKey.Task(normalizedAbsolutePath)).version.set(version)
    version
  }

  private def lockFor(key: LockKey, makeLock: => CrossThreadRwLock): CrossThreadRwLock =
    cellFor(key).lock(makeLock)

  private def cellFor(key: LockKey): LockCell =
    locks.computeIfAbsent(key, _ => LockCell())
}

private[mill] object LauncherLockRegistry {
  sealed private trait LockKey
  private object LockKey {
    final case class MetaBuild(depth: Int) extends LockKey
    final case class Task(normalizedAbsolutePath: String) extends LockKey
    case object Exclusive extends LockKey
  }

  private final class LockCell {
    @volatile private var lock0: CrossThreadRwLock = null
    val version: AtomicLong = new AtomicLong(0L)

    def lock(makeLock: => CrossThreadRwLock): CrossThreadRwLock = {
      val existing = lock0
      if (existing != null) existing
      else this.synchronized {
        if (lock0 == null) lock0 = makeLock
        lock0
      }
    }
  }
  private object LockCell {
    def apply(): LockCell = new LockCell
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
