package mill.daemon

import mill.api.Val
import mill.api.daemon.internal.TaskApi
import mill.exec.GroupExecution

import scala.collection.mutable

/**
 * Process-level shared worker cache that allows worker objects to be reused
 * across concurrent and sequential daemon commands.
 *
 * Workers are thread-safe, so daemon evaluations can safely share the same
 * mutable map reference. Each depth level keeps its own worker map scoped to
 * the classloader identity at that depth; when the classloader changes, stale
 * workers are closed and replaced with a fresh empty map.
 */
object SharedWorkerCache {
  private case class DepthEntry(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  )

  private val lock = new Object
  private val caches = mutable.Map.empty[Int, DepthEntry]

  def forDepth(
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] =
    lock.synchronized {
      caches.get(depth) match {
        case Some(entry) if entry.classLoaderIdentityHash == classLoaderIdentityHash =>
          entry.workers
        case staleOpt =>
          staleOpt.foreach(stale => closeWorkers(stale.workers))
          val fresh = mutable.Map.empty[String, (Int, Val, TaskApi[?])]
          caches(depth) = DepthEntry(classLoaderIdentityHash, fresh)
          fresh
      }
    }

  // Close workers in reverse topological order so a worker's close() sees its
  // downstream workers still alive. Matches the intra-run invalidation path in
  // GroupExecution.loadUpToDateWorker.
  private def closeWorkers(workers: mutable.Map[String, (Int, Val, TaskApi[?])]): Unit =
    workers.synchronized {
      val deps = GroupExecution.workerDependencies(workers.toMap)
      val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
      GroupExecution.closeWorkersInReverseTopologicalOrder(
        topoIndex.keys,
        workers,
        topoIndex,
        closeable =>
          try closeable.close()
          catch { case _: Throwable => }
      )
    }
}
