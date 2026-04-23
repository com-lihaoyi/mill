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
  private case class CacheKey(workspaceRoot: String, depth: Int)

  private case class DepthEntry(
      classLoaderIdentityHash: Int,
      workers: mutable.Map[String, (Int, Val, TaskApi[?])]
  )

  private val lock = new Object
  private val caches = mutable.Map.empty[CacheKey, DepthEntry]

  def forDepth(
      workspaceRoot: os.Path,
      depth: Int,
      classLoaderIdentityHash: Int
  ): mutable.Map[String, (Int, Val, TaskApi[?])] =
    lock.synchronized {
      val key = CacheKey(workspaceRoot.toString, depth)
      caches.get(key) match {
        case Some(entry) if entry.classLoaderIdentityHash == classLoaderIdentityHash =>
          entry.workers
        case staleOpt =>
          staleOpt.foreach(stale => closeWorkers(stale.workers))
          val fresh = mutable.Map.empty[String, (Int, Val, TaskApi[?])]
          caches(key) = DepthEntry(classLoaderIdentityHash, fresh)
          fresh
      }
    }

  def closeAll(): Unit = lock.synchronized {
    caches.valuesIterator.foreach(entry => closeWorkers(entry.workers))
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
