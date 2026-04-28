package mill.daemon

import mill.api.{MillURLClassLoader, Val}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, TaskApi}

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/**
 * Daemon-wide bootstrap cache shared across concurrent launcher runs.
 *
 * Each stored frame represents reusable metadata from one level of `build.mill`
 * evaluation: watches, classloaders, code signatures, classpaths, and worker
 * caches that are safe to share between launchers. A frame whose bootstrap
 * succeeded carries a [[RunnerSharedState.Frame.Reusable]] payload (which now
 * also owns the per-classloader worker cache, so worker lifetime tracks
 * classloader lifetime); a failed frame still publishes its watches so callers
 * can use them to invalidate.
 */
case class RunnerSharedState(
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty,
    /**
     * Most recently published user-level (depth = 0) `moduleWatched`, kept
     * daemon-wide so a subsequent launcher's depth-1 reusable check can detect
     * that user-level inputs (e.g. `BuildCtx.watchValue` results) changed since
     * the previous run, and force the meta-build classloader to be recreated.
     * Only `processRunClasspath` publishes per-depth meta-build frames, so the
     * depth-0 final-frame `moduleWatched` would otherwise have nowhere to live
     * across launcher invocations.
     */
    userFinalModuleWatched: Option[Seq[Watchable]] = None,
    /**
     * Daemon-wide worker cache for the deepest meta-build level (whose nested
     * level has no own classloader, e.g. a project without `mill-build/`).
     * Workers at this level are loaded via the daemon's main classloader, so
     * their class types and input hashes are stable across launchers — sharing
     * avoids re-creating them (and re-creating their `internalWorkerClassLoader`)
     * on every launcher that re-evaluates the deepest level. Lifetime is the
     * daemon JVM's; entries are only removed when their inputs change and Mill
     * itself displaces them via `loadUpToDateWorker`.
     *
     * Reference is stable across `copy(...)` since case-class copy preserves
     * untouched fields, so all snapshots of the state share the same map.
     */
    bootstrapWorkers: mutable.Map[String, (Int, Val, TaskApi[?])] =
      mutable.Map.empty[String, (Int, Val, TaskApi[?])]
) {
  import RunnerSharedState.*

  def frameAt(depth: Int): Option[Frame] = frames.get(depth)

  def reusableFrameAt(depth: Int): Option[Frame.Reusable] =
    frames.get(depth).flatMap(_.reusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    if (depth == 0) userFinalModuleWatched
    else frames.get(depth).map(_.moduleWatched)

  def withFrame(depth: Int, frame: Frame): RunnerSharedState =
    copy(frames = frames.updated(depth, frame))

  def withUserFinalModuleWatched(moduleWatched: Seq[Watchable]): RunnerSharedState =
    copy(userFinalModuleWatched = Some(moduleWatched))
}

object RunnerSharedState {
  def empty: RunnerSharedState = RunnerSharedState()

  /**
   * One shared bootstrap-frame entry. `reusable` is set iff the bootstrap at
   * this depth succeeded; failed bootstraps still publish a Frame so we can
   * read their watches for invalidation purposes.
   */
  case class Frame(
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      reusable: Option[Frame.Reusable]
  )

  object Frame {
    case class Reusable(
        classLoader: MillURLClassLoader,
        runClasspath: Seq[PathRefApi],
        compileOutput: PathRefApi,
        codeSignatures: Map[String, Int],
        buildOverrideFiles: Map[java.nio.file.Path, String],
        // Mutable so launchers can refresh it after a finalTasks evaluation
        // that mutated meta-build inputs (e.g. spotless reformatting build.mill):
        // without an in-place update, the next launcher's `probeSelectiveReuse`
        // would compare current files against stale metadata and rebuild the
        // meta-build classloader, wiping cached workers.
        selectiveMetadata: AtomicReference[Option[String]] =
          new AtomicReference[Option[String]](None),
        // Workers loaded from this classloader and shared across launchers using
        // this frame. Lifetime tracks classloader lifetime: when this frame is
        // displaced, [[closeWorkers]] is called as part of disposing the
        // classloader, ensuring no stale workers outlive their classloader.
        workers: mutable.Map[String, (Int, Val, TaskApi[?])] =
          mutable.Map.empty[String, (Int, Val, TaskApi[?])]
    )
  }
}
