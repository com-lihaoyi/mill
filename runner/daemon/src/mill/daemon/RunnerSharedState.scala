package mill.daemon

import mill.api.{MillURLClassLoader, Val}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.{PathRefApi, TaskApi, internal}
import mill.api.internal.RootModule
import mill.exec.GroupExecution

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

@internal
case class RunnerSharedState(
    frames: Map[Int, RunnerSharedState.Frame] = Map.empty,
    workerCaches: Map[Int, RunnerSharedState.WorkerCacheSlot] = Map.empty,
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

  def withWorkerCache(depth: Int, workerCache: WorkerCacheSlot): RunnerSharedState =
    copy(workerCaches = workerCaches.updated(depth, workerCache))

  def withBootstrap(module: RootModule, buildFile: String, usesDummy: Boolean): RunnerSharedState =
    copy(
      bootstrapModule = Some(module),
      bootstrapBuildFile = Some(buildFile),
      bootstrapUsesDummy = Some(usesDummy)
    )
}

object RunnerSharedState {
  def empty: RunnerSharedState = RunnerSharedState()

  case class Frame(
      evalWatched: Seq[Watchable] = Nil,
      moduleWatched: Option[Seq[Watchable]] = None,
      classLoaderOpt: Option[MillURLClassLoader] = None,
      runClasspath: Seq[PathRefApi] = Nil,
      compileOutputOpt: Option[PathRefApi] = None,
      codeSignatures: Map[String, Int] = Map.empty,
      buildOverrideFiles: Map[java.nio.file.Path, String] = Map.empty,
      selectiveMetadata: Option[String] = None
  ) {
    def hasReusable: Boolean = classLoaderOpt.nonEmpty
  }

  final case class WorkerCacheSlot(
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
