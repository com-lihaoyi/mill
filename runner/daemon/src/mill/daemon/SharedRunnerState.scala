package mill.daemon

import mill.api.MillURLClassLoader
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, internal}
import mill.api.internal.RootModule

/**
 * Daemon-wide state shared across concurrent launchers, held in an
 * `AtomicReference[SharedRunnerState]` on [[MillDaemonMain0]]. Contains only
 * data that is deterministic in the meta-build source and safe to share.
 *
 * - [[frames]] is keyed by meta-build depth; absent entries simply mean "nothing
 *   published at that depth yet". Writes are sequenced by a single process-wide
 *   meta-build write lock (see [[mill.api.daemon.WorkspaceLocking.metaBuildResource]]);
 *   concurrent launchers can read while a writer holds the lock only after it
 *   downgrades to read, which happens after the frame is installed here.
 *
 * Each [[SharedRunnerState.Frame]] carries:
 * - [[SharedRunnerState.Frame.reusable]]: the currently-published
 *   [[ReusableFrame]] at that depth, if any.
 * - [[SharedRunnerState.Frame.moduleWatched]]: the module-level watches
 *   recorded by the most recent launcher to run at that depth. A later launcher
 *   consults it to decide whether anything watched under the currently-published
 *   classloader has changed, which in turn is a reason to refresh the classloader.
 *
 * [[bootstrapModule]] / [[bootstrapBuildFile]] cache the in-process bootstrap
 * module across launchers, keyed by the build file name so a rename invalidates
 * the cached instance. Populated only on successful bootstrap; on failure the
 * slots stay empty so the next launcher retries.
 */
@internal
case class SharedRunnerState(
    frames: Map[Int, SharedRunnerState.Frame] = Map.empty,
    bootstrapModule: Option[RootModule] = None,
    bootstrapBuildFile: Option[String] = None
) {
  import SharedRunnerState.*

  def frameAt(depth: Int): Option[ReusableFrame] =
    frames.get(depth).flatMap(_.reusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frames.get(depth).flatMap(_.moduleWatched)

  def withFrame(depth: Int, frame: ReusableFrame): SharedRunnerState =
    copy(frames = frames.updated(depth, at(depth).copy(reusable = Some(frame))))

  def withModuleWatched(depth: Int, watched: Seq[Watchable]): SharedRunnerState =
    copy(frames = frames.updated(depth, at(depth).copy(moduleWatched = Some(watched))))

  def withBootstrap(module: RootModule, buildFile: String): SharedRunnerState =
    copy(bootstrapModule = Some(module), bootstrapBuildFile = Some(buildFile))

  private def at(depth: Int): Frame = frames.getOrElse(depth, Frame())
}

object SharedRunnerState {
  def empty: SharedRunnerState = SharedRunnerState()

  /** One depth's shared state: the published [[ReusableFrame]] (if any) and the
   *  most recent moduleWatched snapshot recorded at that depth. */
  case class Frame(
      reusable: Option[ReusableFrame] = None,
      moduleWatched: Option[Seq[Watchable]] = None
  )

  /**
   * The deterministic meta-build outputs at a given depth: classloader, compiled
   * classpath, code signatures, etc. Produced when a meta-build compiles
   * successfully, and safe to share across concurrent launchers because every
   * field is a pure function of the meta-build source.
   */
  @internal
  case class ReusableFrame(
      classLoader: MillURLClassLoader,
      runClasspath: Seq[PathRefApi],
      compileOutput: PathRefApi,
      codeSignatures: Map[String, Int],
      buildOverrideFiles: Map[java.nio.file.Path, String],
      workerCacheSummary: Map[String, LauncherRunnerState.Frame.WorkerInfo]
  )
}
