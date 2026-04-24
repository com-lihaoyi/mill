package mill.daemon

import mill.api.MillURLClassLoader
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, internal}
import mill.api.internal.RootModule

/**
 * Daemon-wide state shared across concurrent launchers, held in an
 * `AtomicReference[RunnerSharedState]` on [[MillDaemonMain0]]. Contains only
 * data that is deterministic in the meta-build source and safe to share.
 *
 * - [[frames]] is keyed by meta-build depth; absent entries simply mean "nothing
 *   published at that depth yet". Writes are sequenced by the corresponding
 *   per-depth meta-build write lock (see [[mill.api.daemon.internal.LauncherLocking.metaBuildLock]]).
 *   Published frames are immutable once installed; launchers pin and reuse them
 *   under read leases rather than mutating them on the read path.
 *
 * Each [[RunnerSharedState.Frame]] carries:
 * - the currently-published reusable meta-build payload at that depth, if any
 * - [[RunnerSharedState.Frame.moduleWatched]]: the module-level watches
 *   recorded by the most recent launcher to run at that depth. A later launcher
 *   consults it to decide whether anything watched under the currently-published
 *   classloader has changed, which in turn is a reason to refresh the classloader.
 *
 * [[bootstrapModule]] / [[bootstrapBuildFile]] / [[bootstrapUsesDummy]] cache the
 * in-process bootstrap module across launchers, keyed by both the discovered
 * root build file name and whether we had to synthesize the lightweight
 * script-only bootstrap. Populated only on successful bootstrap; on failure the
 * slots stay empty so the next launcher retries.
 */
@internal
case class RunnerSharedState(
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty,
    bootstrapModule: Option[RootModule] = None,
    bootstrapBuildFile: Option[String] = None,
    bootstrapUsesDummy: Option[Boolean] = None
) {
  import RunnerSharedState.*

  def frameAt(depth: Int): Option[Frame] =
    frames.get(depth).filter(_.hasReusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frames.get(depth).flatMap(_.moduleWatched)

  def withFrame(depth: Int, frame: Frame): RunnerSharedState =
    copy(frames = frames.updated(depth, frame))

  def withModuleWatched(depth: Int, watched: Seq[Watchable]): RunnerSharedState =
    copy(frames = frames.updated(depth, at(depth).copy(moduleWatched = Some(watched))))

  def withBootstrap(module: RootModule, buildFile: String, usesDummy: Boolean): RunnerSharedState =
    copy(
      bootstrapModule = Some(module),
      bootstrapBuildFile = Some(buildFile),
      bootstrapUsesDummy = Some(usesDummy)
    )

  private def at(depth: Int): Frame = frames.getOrElse(depth, Frame())
}

object RunnerSharedState {
  def empty: RunnerSharedState = RunnerSharedState()

  /**
   * One depth's shared state: the published reusable meta-build payload (if any)
   * and the most recent moduleWatched snapshot recorded at that depth.
   */
  case class Frame(
      evalWatched: Seq[Watchable] = Nil,
      moduleWatched: Option[Seq[Watchable]] = None,
      classLoaderOpt: Option[MillURLClassLoader] = None,
      runClasspath: Seq[PathRefApi] = Nil,
      compileOutputOpt: Option[PathRefApi] = None,
      codeSignatures: Map[String, Int] = Map.empty,
      buildOverrideFiles: Map[java.nio.file.Path, String] = Map.empty,
      workerCacheSummary: Map[String, RunnerLauncherState.Frame.WorkerInfo] = Map.empty,
      selectiveMetadata: Option[String] = None
  ) {
    def hasReusable: Boolean = classLoaderOpt.nonEmpty
  }
}
