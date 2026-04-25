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
 *   recorded for the cached frame. Set together with the rest of the Frame
 *   under the depth-N meta-build write lease in
 *   [[MillBuildBootstrap.processRunClasspath]] and never mutated afterwards
 *   — every Frame's moduleWatched corresponds 1:1 with the cached classloader
 *   in the same Frame, so a launcher consulting depth-N's moduleWatched to
 *   decide whether to invalidate the depth-(N-1) frame always sees a coherent
 *   pair. NB: final-depth (user-task-evaluation) moduleWatched is NOT
 *   published here — it is per-launcher (depends on which modules the user's
 *   command selected) and lives only on
 *   [[RunnerLauncherState.FinalFrame.moduleWatched]].
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

  def withBootstrap(module: RootModule, buildFile: String, usesDummy: Boolean): RunnerSharedState =
    copy(
      bootstrapModule = Some(module),
      bootstrapBuildFile = Some(buildFile),
      bootstrapUsesDummy = Some(usesDummy)
    )
}

object RunnerSharedState {
  def empty: RunnerSharedState = RunnerSharedState()

  /**
   * One depth's shared state: the published reusable meta-build payload (if any)
   * and the most recent moduleWatched snapshot recorded at that depth.
   *
   * `moduleWatched` is published even when evaluation at this depth failed to
   * produce a reusable classloader. That "watch-only" frame ensures later
   * launchers invalidate deeper cached frames against the latest parent-watch
   * set rather than an older successful build's stale watches.
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
