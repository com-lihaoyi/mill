package mill.daemon

import mill.api.MillURLClassLoader
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, internal}

/**
 * Daemon-wide state shared across concurrent launchers, held in an
 * `AtomicReference[SharedMetaBuildState]` on [[MillDaemonMain0]]. Contains only
 * data that is deterministic in the meta-build source and safe to share:
 *
 * - [[framesByDepth]] — the currently-published [[ReusableFrame]] per meta-build
 *   depth. Writes are sequenced by the per-depth meta-build write lock in
 *   [[mill.api.daemon.WorkspaceLocking]]; concurrent launchers can read while a
 *   writer holds the lock only after it downgrades to read, which happens after
 *   the frame is installed here.
 * - [[moduleWatchedByDepth]] — the module-level watches recorded by the most
 *   recent launcher to run final tasks at that depth. A later launcher consults
 *   it to decide whether anything watched under the currently-published
 *   classloader has changed, which in turn is a reason to refresh the classloader.
 */
@internal
case class SharedMetaBuildState(
    framesByDepth: Map[Int, SharedMetaBuildState.ReusableFrame] = Map.empty,
    moduleWatchedByDepth: Map[Int, Seq[Watchable]] = Map.empty
) {
  def frameAt(depth: Int): Option[SharedMetaBuildState.ReusableFrame] = framesByDepth.get(depth)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] = moduleWatchedByDepth.get(depth)

  def withFrame(depth: Int, frame: SharedMetaBuildState.ReusableFrame): SharedMetaBuildState =
    copy(framesByDepth = framesByDepth.updated(depth, frame))

  def withModuleWatched(depth: Int, watched: Seq[Watchable]): SharedMetaBuildState =
    copy(moduleWatchedByDepth = moduleWatchedByDepth.updated(depth, watched))
}

object SharedMetaBuildState {
  def empty: SharedMetaBuildState = SharedMetaBuildState()

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
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: String,
      workerCacheSummary: Map[String, LaunchState.Frame.WorkerInfo]
  )
}
