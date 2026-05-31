package mill.exec

import mill.api.{ExecutionPaths, Logger, PathRef, Task}

private[exec] final class TaskCachePipeline(
    paths: ExecutionPaths,
    cacheEntry: TaskCacheEntry,
    labelled: Task.Named[?],
    inputsHash: Int,
    hasSideEffects: Boolean,
    logger: Logger,
    versionMismatchReasons: java.util.concurrent.ConcurrentHashMap[Task[?], String],
    remoteLocation: Option[String],
    remoteCacheSalt: Option[String],
    workspace: os.Path
) {
  def readLocal(): Option[TaskCacheEntry.Loaded] =
    cacheEntry
      .read(logger, inputsHash, labelled, versionMismatchReasons)
      .map(cached => if (hasSideEffects) cached.copy(valueOpt = None) else cached)

  def loadRemote(taskLocks: TaskLockCoordinator): Boolean =
    remoteLocation.exists { location =>
      taskLocks.blockingOnPool {
        BazelRemoteCache.load(
          paths,
          inputsHash,
          labelled.ctx.segments.render,
          location,
          remoteCacheSalt,
          workspace
        )
      }
    }

  def storeRemote(
      serializedPaths: Seq[PathRef],
      taskLocks: TaskLockCoordinator
  ): Unit =
    remoteLocation.foreach { location =>
      taskLocks.blockingOnPool {
        BazelRemoteCache.store(
          paths,
          inputsHash,
          labelled.ctx.segments.render,
          location,
          remoteCacheSalt,
          serializedPaths,
          workspace
        )
      }
    }
}
