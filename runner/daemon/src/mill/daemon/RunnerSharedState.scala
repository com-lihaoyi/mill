package mill.daemon

import mill.api.{MillURLClassLoader, Val}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, TaskApi}
import mill.api.internal.RootModule
import mill.exec.GroupExecution

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/**
 * Daemon-wide bootstrap cache shared across concurrent launcher runs.
 *
 * Each stored frame represents reusable metadata from one level of `build.mill`
 * evaluation: watches, classloaders, code signatures, classpaths, and worker
 * caches that are safe to share between launchers. A frame whose bootstrap
 * succeeded carries a [[RunnerSharedState.Frame.Reusable]] payload; a failed
 * frame still publishes its watches so callers can use them to invalidate.
 */
case class RunnerSharedState(
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty,
    workerCaches: Map[Int, RunnerSharedState.WorkerCacheSlot] = Map.empty,
    bootstrap: Option[RunnerSharedState.BootstrapCache] = None
) {
  import RunnerSharedState.*

  def frameAt(depth: Int): Option[Frame] = frames.get(depth)

  def reusableFrameAt(depth: Int): Option[Frame.Reusable] =
    frames.get(depth).flatMap(_.reusable)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frames.get(depth).map(_.moduleWatched)

  def withFrame(depth: Int, frame: Frame): RunnerSharedState =
    copy(frames = frames.updated(depth, frame))

  def withWorkerCache(depth: Int, slot: WorkerCacheSlot): RunnerSharedState =
    copy(workerCaches = workerCaches.updated(depth, slot))

  def withBootstrap(cache: BootstrapCache): RunnerSharedState =
    copy(bootstrap = Some(cache))
}

object RunnerSharedState {
  val empty: RunnerSharedState = RunnerSharedState()

  case class BootstrapCache(
      module: RootModule,
      buildFile: String,
      usesDummy: Boolean
  )

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
        selectiveMetadata: Option[String]
    )
  }

  case class WorkerCacheSlot(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  ) extends AutoCloseable {
    override def close(): Unit = workers.synchronized {
      val deps = GroupExecution.workerDependencies(workers.toMap)
      val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
      GroupExecution.closeWorkersInReverseTopologicalOrder(
        topoIndex.keys,
        workers,
        topoIndex,
        c =>
          try c.close()
          catch { case _: Throwable => () }
      )
    }
  }

  /**
   * Return the worker cache for `depth` matching the given classloader identity.
   * If the slot is missing or its identity has changed, atomically install a
   * fresh empty slot and close the displaced one.
   */
  def sharedWorkerCache(
      sharedState: AtomicReference[RunnerSharedState],
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] = {
    val before = sharedState.get()
    val after = sharedState.updateAndGet { current =>
      current.workerCaches.get(depth) match {
        case Some(existing) if existing.classLoaderIdentityHash == classLoaderIdentityHash =>
          current
        case _ =>
          current.withWorkerCache(
            depth,
            WorkerCacheSlot(
              classLoaderIdentityHash,
              mutable.Map.empty[String, (Int, Val, TaskApi[?])]
            )
          )
      }
    }
    val newSlot = after.workerCaches(depth)
    val oldSlot = before.workerCaches.get(depth)
    if (oldSlot.exists(_ ne newSlot)) oldSlot.foreach(_.close())
    newSlot.workers
  }
}
