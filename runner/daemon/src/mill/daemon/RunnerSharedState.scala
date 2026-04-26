package mill.daemon

import mill.api.{MillURLClassLoader, Val}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, TaskApi, internal}
import mill.api.internal.RootModule
import mill.exec.GroupExecution

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/**
 * Daemon-wide bootstrap cache shared across concurrent launcher runs.
 *
 * Each stored frame represents reusable metadata from one level of `build.mill`
 * evaluation: watches, classloaders, code signatures, classpaths, and worker
 * caches that are safe to share between launchers.
 */
@internal
case class RunnerSharedState(
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty,
    workerCaches: Map[Int, RunnerSharedState.WorkerCacheSlot] = Map.empty,
    bootstrap: Option[RunnerSharedState.BootstrapCache] = None
) {
  import RunnerSharedState.*

  def frameAt(depth: Int): Option[Frame] = frames.get(depth)

  def reusableFrameAt(depth: Int): Option[Frame.Reusable] =
    frameAt(depth).collect { case frame: Frame.Reusable => frame }

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    frames.get(depth).map(_.moduleWatched)

  def withFrame(depth: Int, frame: Frame): RunnerSharedState =
    copy(frames = frames.updated(depth, frame))

  def withWorkerCache(depth: Int, workerCache: WorkerCacheSlot): RunnerSharedState =
    copy(workerCaches = workerCaches.updated(depth, workerCache))

  def withBootstrap(cache: BootstrapCache): RunnerSharedState = copy(bootstrap = Some(cache))
}

object RunnerSharedState {
  def empty: RunnerSharedState = RunnerSharedState()

  case class BootstrapCache(
      module: RootModule,
      buildFile: String,
      usesDummy: Boolean
  )

  sealed trait Frame {
    def evalWatched: Seq[Watchable]
    def moduleWatched: Seq[Watchable]
  }

  object Frame {
    case class Failed(
        evalWatched: Seq[Watchable] = Nil,
        moduleWatched: Seq[Watchable] = Nil
    ) extends Frame

    case class Reusable(
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable],
        classLoader: MillURLClassLoader,
        runClasspath: Seq[PathRefApi],
        compileOutput: PathRefApi,
        codeSignatures: Map[String, Int],
        buildOverrideFiles: Map[java.nio.file.Path, String],
        selectiveMetadata: Option[String]
    ) extends Frame
  }

  case class WorkerCacheSlot(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  )

  def sharedWorkerCache(
      sharedState: AtomicReference[RunnerSharedState],
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] = {
    var stale: Option[WorkerCacheSlot] = None
    var slot: WorkerCacheSlot = null

    while (slot == null) {
      val current = sharedState.get()
      current.workerCaches.get(depth) match {
        case Some(existing) if existing.classLoaderIdentityHash == classLoaderIdentityHash =>
          slot = existing
        case existingOpt =>
          val next = WorkerCacheSlot(
            classLoaderIdentityHash = classLoaderIdentityHash,
            workers = mutable.Map.empty[String, (Int, Val, TaskApi[?])]
          )
          if (sharedState.compareAndSet(current, current.withWorkerCache(depth, next))) {
            stale = existingOpt
            slot = next
          }
      }
    }

    stale.foreach(closeWorkerCache)
    slot.workers
  }

  private def closeWorkerCache(slot: WorkerCacheSlot): Unit =
    slot.workers.synchronized {
      val deps = GroupExecution.workerDependencies(slot.workers.toMap)
      val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
      GroupExecution.closeWorkersInReverseTopologicalOrder(
        topoIndex.keys,
        slot.workers,
        topoIndex,
        closeable =>
          try closeable.close()
          catch { case _: Throwable => () }
      )
    }
}
