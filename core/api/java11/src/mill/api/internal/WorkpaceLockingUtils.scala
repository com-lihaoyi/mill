package mill.api.internal

import mill.api.internal.WorkspaceLocking.*
import mill.constants.DaemonFiles

import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

private object WorkpaceLockingUtils {
  // Monotonic tiebreaker so that two InProcessManagers created in the same
  // millisecond still get distinct runIds.
  val nextTiebreaker = new AtomicLong(0L)

  // Semaphore-based read-write lock that is NOT thread-bound (unlike ReentrantReadWriteLock).
  // This is critical because task read locks are acquired on thread-pool worker threads but
  // released on the main thread when retainedTerminalReadLocks is drained.
  // Read = acquire(1), Write = acquire(maxPermits). Fair ordering ensures no starvation.
  val maxPermits = 1_000_000

  case class LockOwner(runId: String, pid: Long, command: String)
  case class LockOwnerCount(owner: LockOwner, var count: Int)

  final class ResourceState {
    val semaphore = new Semaphore(maxPermits, true)
    var refCount = 0
    var writeOwnerOpt: Option[LockOwner] = None
    val readOwners = scala.collection.mutable.LinkedHashMap.empty[String, LockOwnerCount]
  }

  private val resourceStates = scala.collection.mutable.HashMap.empty[String, ResourceState]
  private val resourceStatesLock = new Object

  val runRootDirName = "mill-run"
  private val legacyRunDirPrefix = "mill-run-"
  private val maxRetainedRuns = 10
  private val inactiveRunCleanupGracePeriodMillis = 5.seconds.toMillis

  case class ActiveRun(
      runId: String,
      runDir: os.Path,
      consoleTail: os.Path,
      var active: Boolean = true,
      var inactiveSinceMillis: Long = 0L,
      var published: Boolean = false,
      publishedFiles: scala.collection.mutable.Map[os.Path, os.Path] =
        scala.collection.mutable.LinkedHashMap.empty
  )

  final class OutCoordinator(out: os.Path) {
    private val lock = new Object
    private val runs = scala.collection.mutable.LinkedHashMap.empty[String, ActiveRun]

    def register(run: ActiveRun): Unit = lock.synchronized { runs.update(run.runId, run) }

    def publish(run: ActiveRun): Unit = lock.synchronized {
      if (!run.published) {
        run.published = true
        refreshWellKnownLinks()
      }
    }

    def recordPublishedFile(run: ActiveRun, link: os.Path, target: os.Path): Unit =
      lock.synchronized {
        run.publishedFiles.update(link, target)
        refreshWellKnownLinks()
      }

    def deactivate(run: ActiveRun): Unit = lock.synchronized {
      run.active = false
      run.inactiveSinceMillis = System.currentTimeMillis()
      refreshWellKnownLinks()
    }

    def cleanupOldRunDirs(): Unit = {
      try {
        if (os.exists(out)) {
          lock.synchronized {
            val now = System.currentTimeMillis()
            val protectedRunDirs = runs.valuesIterator
              .filter(run =>
                run.active ||
                  run.inactiveSinceMillis == 0L ||
                  now - run.inactiveSinceMillis < inactiveRunCleanupGracePeriodMillis
              )
              .map(_.runDir)
              .toSet

            val runRootDir = out / runRootDirName
            val runDirs =
              if (os.exists(runRootDir)) os.list(runRootDir).filter(os.isDir(_)).sortBy(_.last)
              else Seq.empty

            val legacyRunDirs = os.list(out)
              .filter(p => os.isDir(p) && p.last.startsWith(legacyRunDirPrefix))
              .sortBy(_.last)

            val removable = runDirs.filterNot(protectedRunDirs)
            val toRemove = removable.take(runDirs.size - maxRetainedRuns)
            (toRemove ++ legacyRunDirs).foreach(dir =>
              try os.remove.all(dir)
              catch { case _: Throwable => }
            )

            val before = runs.size
            runs.retain((_, run) => os.exists(run.runDir))
            if (runs.size != before) refreshWellKnownLinks()
          }
        }
      } catch {
        case _: Throwable =>
      }
    }

    private def refreshWellKnownLinks(): Unit = {
      val publishedRuns = runs.valuesIterator.filter(_.published).toSeq

      updateSymlink(out / DaemonFiles.millConsoleTail, publishedRuns.lastOption.map(_.consoleTail))
      val publishedLinks = runs.valuesIterator.flatMap(_.publishedFiles.keys).toSet
      publishedLinks.foreach { link =>
        updateSymlink(
          link,
          publishedRuns.reverseIterator.flatMap(_.publishedFiles.get(link)).nextOption()
        )
      }
    }
  }

  private val outCoordinators = new ConcurrentHashMap[String, OutCoordinator]()

  def coordinatorFor(out: os.Path): OutCoordinator =
    outCoordinators.computeIfAbsent(out.toString, _ => new OutCoordinator(out))

  private def updateSymlink(link: os.Path, targetOpt: Option[os.Path]): Unit = {
    try {
      targetOpt match {
        case Some(target) =>
          os.makeDir.all(link / os.up)
          val rel = target.relativeTo(link / os.up)
          val tmp =
            link / os.up / s".${link.last}.tmp-${System.nanoTime()}-${nextTiebreaker.getAndIncrement()}"
          try {
            try os.remove.all(tmp)
            catch { case _: Throwable => }
            os.symlink(tmp, rel)
            java.nio.file.Files.move(
              tmp.toNIO,
              link.toNIO,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE
            )
          } finally {
            try os.remove.all(tmp)
            catch { case _: Throwable => }
          }
        case None =>
          try os.remove.all(link)
          catch { case _: Throwable => }
      }
    } catch {
      case _: Throwable =>
    }
  }

  private def resourceState(resource: Resource): ResourceState =
    resourceStates.getOrElseUpdate(resource.key, new ResourceState)

  def retainResourceState(resource: Resource): ResourceState = resourceStatesLock.synchronized {
    val state = resourceState(resource)
    state.refCount += 1
    state
  }

  def releaseResourceState(resource: Resource, state: ResourceState): Unit =
    resourceStatesLock.synchronized {
      state.refCount -= 1
      if (state.refCount <= 0 && state.writeOwnerOpt.isEmpty && state.readOwners.isEmpty) {
        resourceStates.get(resource.key).filter(_ eq state).foreach(_ => resourceStates.remove(resource.key))
      }
    }

  def registerOwner(resource: Resource, state: ResourceState, owner: LockOwner): Unit = {
    resourceStatesLock.synchronized {
      resource.kind match {
        case LockKind.Write =>
          state.writeOwnerOpt match {
            case Some(existing) =>
              throw new IllegalStateException(
                s"Resource ${resource.key} already has write owner ${existing.runId}"
              )
            case None =>
              state.writeOwnerOpt = Some(owner)
          }
        case LockKind.Read =>
          state.readOwners.get(owner.runId) match {
            case Some(existing) => existing.count += 1
            case None => state.readOwners(owner.runId) = LockOwnerCount(owner, 1)
          }
      }
    }
  }

  def unregisterOwner(
      resource: Resource,
      state: ResourceState,
      owner: LockOwner,
      kind: LockKind
  ): Unit = {
    resourceStatesLock.synchronized {
      if (resourceStates.get(resource.key).contains(state)) {
        kind match {
          case LockKind.Write =>
            state.writeOwnerOpt match {
              case Some(existing) if existing.runId == owner.runId =>
                state.writeOwnerOpt = None
              case _ =>
            }
          case LockKind.Read =>
            state.readOwners.get(owner.runId).foreach { existing =>
              existing.count -= 1
              if (existing.count <= 0) state.readOwners.remove(owner.runId)
            }
        }

        if (state.refCount <= 0 && state.writeOwnerOpt.isEmpty && state.readOwners.isEmpty) {
          resourceStates.get(resource.key).filter(_ eq state).foreach(_ => resourceStates.remove(resource.key))
        }
      }
    }
  }

  def downgradeOwner(resource: Resource, state: ResourceState, owner: LockOwner): Unit = {
    resourceStatesLock.synchronized {
      if (resourceStates.get(resource.key).contains(state)) {
        state.writeOwnerOpt match {
          case Some(existing) if existing.runId == owner.runId =>
            state.writeOwnerOpt = None
          case _ =>
        }
        state.readOwners.get(owner.runId) match {
          case Some(existing) => existing.count += 1
          case None => state.readOwners(owner.runId) = LockOwnerCount(owner, 1)
        }
      }
    }
  }

  def blockingOwner(resource: Resource): Option[LockOwner] = {
    resourceStatesLock.synchronized {
      resourceStates.get(resource.key) match {
        case None => None
        case Some(state) =>
          resource.kind match {
            case LockKind.Read =>
              state.writeOwnerOpt
            case LockKind.Write =>
              state.writeOwnerOpt.orElse(state.readOwners.headOption.map(_._2.owner))
          }
      }
    }
  }
}
