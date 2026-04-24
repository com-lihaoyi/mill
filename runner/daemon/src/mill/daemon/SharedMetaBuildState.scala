package mill.daemon

import mill.api.MillURLClassLoader
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, internal}

/**
 * Daemon-wide state shared across concurrent launchers, held in an
 * `AtomicReference[SharedMetaBuildState]` on [[MillDaemonMain0]]. Contains only
 * data that is deterministic in the meta-build source and safe to share:
 *
 * - [[frames]] is indexed by meta-build depth. [[SharedMetaBuildState.Frame.empty]]
 *   fills depths that have no published frame yet. Writes to a given depth are
 *   sequenced by the per-depth meta-build write lock in
 *   [[mill.api.daemon.WorkspaceLocking]]; concurrent launchers can read while a
 *   writer holds the lock only after it downgrades to read, which happens after
 *   the frame is installed here.
 *
 * Each [[SharedMetaBuildState.Frame]] carries:
 * - [[SharedMetaBuildState.Frame.reusable]]: the currently-published
 *   [[ReusableFrame]] at that depth, if any.
 * - [[SharedMetaBuildState.Frame.moduleWatched]]: the module-level watches
 *   recorded by the most recent launcher to run at that depth. A later launcher
 *   consults it to decide whether anything watched under the currently-published
 *   classloader has changed, which in turn is a reason to refresh the classloader.
 */
@internal
case class SharedMetaBuildState(
    frames: Seq[SharedMetaBuildState.Frame] = Nil
) {
  import SharedMetaBuildState.*

  def frameAt(depth: Int): Option[ReusableFrame] =
    if (depth < 0) None else frames.lift(depth).flatMap(_.reusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    if (depth < 0) None else frames.lift(depth).flatMap(_.moduleWatched)

  def withFrame(depth: Int, frame: ReusableFrame): SharedMetaBuildState =
    copy(frames = updateAt(depth, _.copy(reusable = Some(frame))))

  def withModuleWatched(depth: Int, watched: Seq[Watchable]): SharedMetaBuildState =
    copy(frames = updateAt(depth, _.copy(moduleWatched = Some(watched))))

  private def updateAt(depth: Int, f: Frame => Frame): Seq[Frame] = {
    require(depth >= 0, s"depth must be non-negative, got $depth")
    val padded = frames.padTo(depth + 1, Frame.empty)
    padded.updated(depth, f(padded(depth)))
  }
}

object SharedMetaBuildState {
  def empty: SharedMetaBuildState = SharedMetaBuildState()

  /** One depth's shared state: the published [[ReusableFrame]] (if any) and the
   *  most recent moduleWatched snapshot recorded at that depth. */
  case class Frame(
      reusable: Option[ReusableFrame] = None,
      moduleWatched: Option[Seq[Watchable]] = None
  ) {
    def nonEmpty: Boolean = reusable.nonEmpty || moduleWatched.nonEmpty
  }
  object Frame {
    def empty: Frame = Frame()
  }

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
