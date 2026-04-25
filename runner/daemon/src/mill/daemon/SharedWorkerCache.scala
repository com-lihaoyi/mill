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
 *
 * Lifecycle / classloader contract:
 *
 * Stale workers are closed when the depth's classloader identity changes; the
 * caller (e.g. [[mill.daemon.MillBuildBootstrap.processRunClasspath]]) holds
 * the depth's meta-build write lease across the publish window, so any
 * concurrent launcher that pinned the old classloader via a meta-build read
 * lease has either already released its lease (in which case the close is
 * safe) or is still holding it (in which case the writer-preferring lock
 * blocks the publish until it releases).
 *
 * Invariant for worker authors: a worker's `close()` must only access classes
 * loaded from the same classloader the worker was constructed under. It must
 * not transitively dereference downstream worker classloaders that have
 * already unloaded — the reverse-topological close order in
 * [[closeWorkers]] guarantees downstream workers are still alive when an
 * upstream worker's close runs at the same depth, but it does NOT extend
 * across meta-build depths.
 */
object SharedWorkerCache {
  private case class DepthEntry(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  )

  private val caches = new java.util.concurrent.ConcurrentHashMap[Int, DepthEntry]()

  def forDepth(
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] = {
    // Capture any stale entry to close *outside* the swap. Worker close() can
    // be slow (Zinc shutdown, classloader unload, native resource teardown);
    // doing it under a global lock would block every other depth's lookup for
    // its duration. The caller holds the per-depth meta-build write lease, so
    // the depth's slot won't race with itself; lookups at other depths are
    // independent.
    var stale: Option[mutable.Map[String, (Int, Val, TaskApi[?])]] = None
    val entry = caches.compute(
      depth,
      (_, existing) =>
        if (existing != null && existing.classLoaderIdentityHash == classLoaderIdentityHash)
          existing
        else {
          if (existing != null) stale = Some(existing.workers)
          DepthEntry(
            classLoaderIdentityHash,
            mutable.Map.empty[String, (Int, Val, TaskApi[?])]
          )
        }
    )
    stale.foreach(closeWorkers)
    entry.workers
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
