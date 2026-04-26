package mill.daemon

import mill.api.{MillURLClassLoader, Val}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, TaskApi}

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
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty
) {
  import RunnerSharedState.*

  def frameAt(depth: Int): Option[Frame] = frames.get(depth)

  def reusableFrameAt(depth: Int): Option[Frame.Reusable] =
    frames.get(depth).flatMap(_.reusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frames.get(depth).map(_.moduleWatched)

  def withFrame(depth: Int, frame: Frame): RunnerSharedState =
    copy(frames = frames.updated(depth, frame))
}

object RunnerSharedState {
  val empty: RunnerSharedState = RunnerSharedState()

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
        selectiveMetadata: Option[String],
        // Workers loaded from this classloader and shared across launchers using
        // this frame. Lifetime tracks classloader lifetime: when this frame is
        // displaced, [[closeWorkers]] is called as part of disposing the
        // classloader, ensuring no stale workers outlive their classloader.
        workers: mutable.Map[String, (Int, Val, TaskApi[?])] =
          mutable.Map.empty[String, (Int, Val, TaskApi[?])]
    )
  }
}
