package mill.daemon

import mill.api.Val
import mill.api.daemon.internal.TaskApi

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
  private val caches = new java.util.concurrent.ConcurrentHashMap[Int, DepthEntry]()

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
