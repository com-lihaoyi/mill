package mill.daemon

import mill.api.Val
import mill.api.daemon.internal.TaskApi

import scala.collection.mutable

/**
 * Process-level shared worker cache that allows worker objects to be reused
 * across concurrent and sequential daemon commands.
 *
 * Workers are thread-safe (they already support concurrent usage from multiple
 * downstream tasks within a single evaluation), so we can safely share the same
 * mutable map reference across concurrent commands. Each depth level (meta-build
 * or final build) has its own worker map, scoped to the classloader identity at
 * that depth. When the classloader changes (e.g., meta-build recompilation),
 * all workers at that depth are closed and a fresh map is created.
 */
object SharedWorkerCache {
  private case class DepthEntry(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  )

  private val lock = new Object
  private val caches = new java.util.concurrent.ConcurrentHashMap[Int, DepthEntry]()

  /**
   * Returns the shared worker map for `depth`. If the classloader identity
   * matches the existing entry, returns the live map. If the classloader has
   * changed, closes old workers and returns a fresh empty map.
   */
  def forDepth(
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] =
    lock.synchronized {
      caches.get(depth) match {
        case entry if entry != null && entry.classLoaderIdentityHash == classLoaderIdentityHash =>
          entry.workers
        case stale =>
          if (stale != null) closeWorkers(stale.workers)
          val fresh = mutable.Map.empty[String, (Int, Val, TaskApi[?])]
          caches.put(depth, DepthEntry(classLoaderIdentityHash, fresh))
          fresh
      }
    }

  /** Close all cached workers across all depths (e.g., on daemon shutdown). */
  def closeAll(): Unit = lock.synchronized {
    caches.forEach { (_, entry) => closeWorkers(entry.workers) }
    caches.clear()
  }

  private def closeWorkers(workers: mutable.Map[String, (Int, Val, TaskApi[?])]): Unit =
    workers.synchronized {
      workers.valuesIterator.foreach {
        case (_, Val(closeable: AutoCloseable), _) =>
          try closeable.close()
          catch { case _: Throwable => }
        case _ =>
      }
      workers.clear()
    }
}
