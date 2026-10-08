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

  /**
   * Drop frames at depths greater than `maxDepth`. Used after a launcher
   * recursion completes to evict frames left over from a previous run that
   * went deeper (e.g. when `mill-build/build.mill` was deleted between runs);
   * without this, classloaders/workers at those depths are never displaced
   * and leak for the daemon's lifetime.
   */
  def withoutFramesAbove(maxDepth: Int): (RunnerSharedState, Map[Int, Frame]) = {
    val (kept, dropped) = frames.partition(_._1 <= maxDepth)
    (copy(frames = kept), dropped)
  }

  def withUserFinalModuleWatched(moduleWatched: Seq[Watchable]): RunnerSharedState =
    copy(userFinalModuleWatched = Some(moduleWatched))
}

object RunnerSharedStateOps {

  /**
   * Close every classloader/worker still pinned by `state.frames`. Idempotent
   * on per-frame errors (each close is guarded). Intended for daemon teardown
   * (both the JVM shutdown hook and the regular embedded `close()` path).
   *
   * Does NOT touch `bootstrapWorkers`: those workers live on the daemon's main
   * classloader, which the JVM owns; closing them here would race with any
   * in-flight launcher that still holds a reference. Daemon teardown waits for
   * launcher sessions to drain via `LauncherLockingImpl.close()` first.
   */
  def closeAll(state: RunnerSharedState): Unit = {
    state.frames.values.foreach { frame =>
      frame.reusable.foreach { reusable =>
        val snapshot = reusable.workers.synchronized(reusable.workers.toMap)
        val deps = mill.exec.GroupExecution.workerDependencies(snapshot)
        val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
        try mill.exec.GroupExecution.closeWorkersInReverseTopologicalOrder(
            topoIndex.keys,
            reusable.workers,
            topoIndex,
            c =>
              try c.close()
              catch { case _: Throwable => () }
          )
        catch { case _: Throwable => () }
        try reusable.classLoader.close()
        catch { case _: Throwable => () }
      }
    }
  }
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
        // Mutable so a finalTasks evaluation that mutates meta-build
        // inputs (e.g. spotless reformat) can refresh in place; without
        // this the next launcher would force a meta-build rebuild and
        // wipe cached workers.
        selectiveMetadata: AtomicReference[Option[String]] =
          new AtomicReference[Option[String]](None),
        // Workers loaded from this classloader; closed alongside the
        // classloader on frame displacement.
        workers: mutable.Map[String, (Int, Val, TaskApi[?])] =
          mutable.Map.empty[String, (Int, Val, TaskApi[?])]
    )
  }
}
