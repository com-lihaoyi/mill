package mill.api.daemon

import mill.constants.DaemonFiles

import java.io.PrintStream
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  case class Resource(key: String, kind: LockKind)

  trait Lease extends AutoCloseable
  trait ResourceLease extends Lease {
    def resource: Resource
    def kind: LockKind
    def downgradeToRead(): ResourceLease
  }

  trait Manager extends AutoCloseable {
    def runId: String
    // Use java.nio.file.Path (not os.Path) in the trait interface because this trait
    // is loaded by the shared/app classloader (mill.api.daemon prefix), while callers
    // like Execution may be loaded by a MillURLClassLoader. os.Path is NOT in the
    // shared prefixes, so using it here would cause LinkageError.
    def consoleTailJava: java.nio.file.Path
    def noBuildLock: Boolean
    def noWaitForBuildLock: Boolean

    /** Returns a per-run path for well-known out/ artifacts in daemon mode. */
    def runFileJava(default: java.nio.file.Path): java.nio.file.Path = default

    def acquireLock(resource: Resource): ResourceLease
    def acquireLocks(resources: Seq[Resource]): Lease
    def withLocks[T](resources: Seq[Resource])(t: => T): T = {
      val lease = acquireLocks(resources)
      try t
      finally lease.close()
    }
  }

  def globalFileResource(path: java.nio.file.Path, kind: LockKind): Resource =
    Resource(s"global:${path.toAbsolutePath.normalize()}", kind)

  def metaBuildResource(depth: Int, kind: LockKind): Resource =
    Resource(s"meta-build:$depth", kind)

  object NoopManager extends Manager {
    override def runId: String = "noop"
    override def consoleTailJava: java.nio.file.Path =
      java.nio.file.Path.of("out", "mill-console-tail")
    override def noBuildLock: Boolean = false
    override def noWaitForBuildLock: Boolean = false
    override def acquireLock(resource0: Resource): ResourceLease = new ResourceLease {
      override def resource: Resource = resource0
      override def kind: LockKind = resource0.kind
      override def downgradeToRead(): ResourceLease = this
      override def close(): Unit = ()
    }
    override def acquireLocks(resources: Seq[Resource]): Lease = () => ()
    override def close(): Unit = ()
  }

  // Monotonic tiebreaker so that two InProcessManagers created in the same
  // millisecond still get distinct runIds.
  private val nextTiebreaker = new AtomicLong(0L)

  // Semaphore-based read-write lock that is NOT thread-bound (unlike ReentrantReadWriteLock).
  // This is critical because task read locks are acquired on thread-pool worker threads but
  // released on the main thread when retainedTerminalReadLocks is drained.
  // Read = acquire(1), Write = acquire(maxPermits). Fair ordering ensures no starvation.
  private val maxPermits = 1_000_000
  // Never evicted; bounded by the number of distinct lock keys (tasks + meta-build depths)
  private val lockTable = new ConcurrentHashMap[String, Semaphore]()

  private val runRootDirName = "mill-run"
  private val legacyRunDirPrefix = "mill-run-"

  /** Maximum number of per-run directories to retain for debugging. */
  private val maxRetainedRuns = 10

  private case class ActiveRun(
      runId: String,
      runDir: os.Path,
      consoleTail: os.Path,
      var active: Boolean = true,
      var published: Boolean = false,
      publishedFiles: scala.collection.mutable.Map[String, os.Path] =
        scala.collection.mutable.LinkedHashMap.empty
  )

  private case class LockOwner(runId: String, pid: Long, command: String)
  private case class LockOwnerCount(owner: LockOwner, var count: Int)
  private class ResourceOwners {
    var writeOwnerOpt: Option[LockOwnerCount] = None
    val readOwners = scala.collection.mutable.LinkedHashMap.empty[String, LockOwnerCount]
  }
  private case class OwnedResource(var kind: LockKind, var count: Int)

  /**
   * Per-out-folder coordination shared across concurrent `InProcessManager`s that target the
   * same out/. Holds the set of active runs and manages the mill-run directory and well-known
   * symlinks under out/.
   */
  private class OutCoordinator(out: os.Path) {
    private val lock = new Object
    private val runs = scala.collection.mutable.LinkedHashMap.empty[String, ActiveRun]

    def register(run: ActiveRun): Unit = lock.synchronized { runs.update(run.runId, run) }

    def publish(run: ActiveRun): Unit = lock.synchronized {
      if (!run.published) {
        run.published = true
        refreshWellKnownLinks()
      }
    }

    def recordPublishedFile(run: ActiveRun, path: os.Path): Unit = lock.synchronized {
      run.publishedFiles.update(path.last, path)
      refreshWellKnownLinks()
    }

    def deactivate(run: ActiveRun): Unit = lock.synchronized {
      run.active = false
      refreshWellKnownLinks()
    }

    /**
     * Clean up old per-run directories in `out/mill-run`, keeping the most recent
     * [[maxRetainedRuns]]. Directories are named `{timestamp}-{counter}` and sort chronologically.
     */
    def cleanupOldRunDirs(): Unit = {
      try {
        if (os.exists(out)) {
          lock.synchronized {
            val activeRunDirs = runs.valuesIterator.filter(_.active).map(_.runDir).toSet

            val runRootDir = out / runRootDirName
            val runDirs =
              if (os.exists(runRootDir)) os.list(runRootDir).filter(os.isDir(_)).sortBy(_.last)
              else Seq.empty

            // Defensive cleanup for old builds that created run directories directly under out/.
            // Current builds place per-run files under out/mill-run/.
            val legacyRunDirs = os.list(out)
              .filter(p => os.isDir(p) && p.last.startsWith(legacyRunDirPrefix))
              .sortBy(_.last)

            val removable = runDirs.filterNot(activeRunDirs)
            val toRemove =
              removable.take(math.min(removable.size, math.max(0, runDirs.size - maxRetainedRuns)))
            toRemove.foreach { dir =>
              try os.remove.all(dir)
              catch { case _: Throwable => }
            }

            legacyRunDirs.foreach { dir =>
              try os.remove.all(dir)
              catch { case _: Throwable => }
            }

            runs.retain((_, run) => os.exists(run.runDir))
          }
        }
      } catch {
        case _: Throwable => // best-effort cleanup
      }
    }

    private def refreshWellKnownLinks(): Unit = {
      val publishedRuns = runs.valuesIterator.filter(_.published).toSeq

      updateSymlink(out / DaemonFiles.millConsoleTail, publishedRuns.lastOption.map(_.consoleTail))
      val publishedNames = runs.valuesIterator.flatMap(_.publishedFiles.keys).toSet
      publishedNames.foreach { fileName =>
        updateSymlink(
          out / fileName,
          publishedRuns.reverseIterator.flatMap(_.publishedFiles.get(fileName)).nextOption()
        )
      }
    }
  }

  private val outCoordinators = new ConcurrentHashMap[String, OutCoordinator]()
  private def coordinatorFor(out: os.Path): OutCoordinator =
    outCoordinators.computeIfAbsent(out.toString, _ => new OutCoordinator(out))

  /** Atomically replace `link` with a relative symlink pointing to `target`. */
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
      case _: Throwable => // best-effort; non-critical for correctness
    }
  }

  private val resourceOwnersTable = new ConcurrentHashMap[String, ResourceOwners]()
  private val resourceOwnersLock = new Object

  private def resourceOwners(resource: Resource): ResourceOwners =
    resourceOwnersTable.computeIfAbsent(resource.key, _ => new ResourceOwners)

  private def registerOwner(resource: Resource, owner: LockOwner): Unit = {
    resourceOwnersLock.synchronized {
      val owners = resourceOwners(resource)
      resource.kind match {
        case LockKind.Write =>
          owners.writeOwnerOpt match {
            case Some(existing) =>
              throw new IllegalStateException(
                s"Resource ${resource.key} already has write owner ${existing.owner.runId}"
              )
            case None =>
              owners.writeOwnerOpt = Some(LockOwnerCount(owner, 1))
          }
        case LockKind.Read =>
          owners.readOwners.get(owner.runId) match {
            case Some(existing) => existing.count += 1
            case None => owners.readOwners(owner.runId) = LockOwnerCount(owner, 1)
          }
      }
    }
  }

  private def unregisterOwner(resource: Resource, owner: LockOwner, kind: LockKind): Unit = {
    resourceOwnersLock.synchronized {
      val owners = resourceOwnersTable.get(resource.key)
      if (owners != null) {
        kind match {
          case LockKind.Write =>
            owners.writeOwnerOpt match {
              case Some(existing) if existing.owner.runId == owner.runId =>
                existing.count -= 1
                if (existing.count <= 0) owners.writeOwnerOpt = None
              case _ =>
            }
          case LockKind.Read =>
            owners.readOwners.get(owner.runId).foreach { existing =>
              existing.count -= 1
              if (existing.count <= 0) owners.readOwners.remove(owner.runId)
            }
        }

        if (owners.writeOwnerOpt.isEmpty && owners.readOwners.isEmpty) {
          resourceOwnersTable.remove(resource.key, owners)
        }
      }
    }
  }

  private def downgradeOwner(resource: Resource, owner: LockOwner): Unit = {
    resourceOwnersLock.synchronized {
      val owners = resourceOwnersTable.get(resource.key)
      if (owners != null) {
        owners.writeOwnerOpt match {
          case Some(existing) if existing.owner.runId == owner.runId =>
            existing.count -= 1
            if (existing.count <= 0) owners.writeOwnerOpt = None
          case _ =>
        }
        owners.readOwners.get(owner.runId) match {
          case Some(existing) => existing.count += 1
          case None => owners.readOwners(owner.runId) = LockOwnerCount(owner, 1)
        }
      }
    }
  }

  private def blockingOwner(resource: Resource): Option[LockOwner] = {
    resourceOwnersLock.synchronized {
      val owners = resourceOwnersTable.get(resource.key)
      if (owners == null) None
      else {
        resource.kind match {
          case LockKind.Read =>
            owners.writeOwnerOpt.map(_.owner)
          case LockKind.Write =>
            owners.writeOwnerOpt
              .map(_.owner)
              .orElse(owners.readOwners.headOption.map(_._2.owner))
        }
      }
    }
  }

  final class InProcessManager(
      out: os.Path,
      daemonDir: os.Path,
      activeCommandMessage: String,
      launcherPid: Long,
      waitingErr: PrintStream,
      override val noBuildLock: Boolean,
      override val noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String =
      s"${System.currentTimeMillis()}-${nextTiebreaker.getAndIncrement()}"

    private val runRootDir: os.Path = out / runRootDirName
    private val runDir: os.Path = runRootDir / runId
    os.makeDir.all(runDir)
    private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.launcherRun(runId))
    private val closed = new AtomicBoolean(false)
    private val ownedResources =
      scala.collection.mutable.LinkedHashMap.empty[String, OwnedResource]

    val consoleTail: os.Path = runDir / "mill-console-tail"
    override def consoleTailJava: java.nio.file.Path = consoleTail.toNIO
    private val owner = LockOwner(runId, launcherPid, activeCommandMessage)

    private val activeRun = ActiveRun(runId, runDir, consoleTail)
    private val coordinator = coordinatorFor(out)

    coordinator.register(activeRun)
    coordinator.cleanupOldRunDirs()
    updateLauncherRunFile()

    private def publishRun(): Unit = coordinator.publish(activeRun)

    private def updateLauncherRunFile(): Unit = {
      if (!closed.get()) {
        val json = ownedResources.synchronized {
          val resourcesJson = ownedResources.iterator.map { case (key, state) =>
            val resourceJson = ujson.write(ujson.Str(key))
            val kindJson = ujson.write(ujson.Str(state.kind.toString))
            s"""{"resource":$resourceJson,"kind":$kindJson,"count":${state.count}}"""
          }.mkString("[", ",", "]")
          val runIdJson = ujson.write(ujson.Str(runId))
          val commandJson = ujson.write(ujson.Str(owner.command))
          s"""{"runId":$runIdJson,"pid":${owner.pid},"command":$commandJson,"resources":$resourcesJson}"""
        }
        if (!closed.get()) {
          mill.api.BuildCtx.withFilesystemCheckerDisabled {
            os.makeDir.all(launcherRunFile / os.up)
            os.write.over(launcherRunFile, json)
          }
        }
      }
    }

    private def registerOwnedResource(resource: Resource): Unit = ownedResources.synchronized {
      ownedResources.get(resource.key) match {
        case Some(existing) =>
          existing.kind = resource.kind
          existing.count += 1
        case None =>
          ownedResources(resource.key) = OwnedResource(resource.kind, 1)
      }
      updateLauncherRunFile()
    }

    private def unregisterOwnedResource(resource: Resource, kind: LockKind): Unit =
      ownedResources.synchronized {
        ownedResources.get(resource.key).foreach { existing =>
          if (existing.kind == kind) {
            existing.count -= 1
            if (existing.count <= 0) ownedResources.remove(resource.key)
          }
        }
        updateLauncherRunFile()
      }

    private def downgradeOwnedResource(resource: Resource): Unit = ownedResources.synchronized {
      ownedResources.get(resource.key).foreach(_.kind = LockKind.Read)
      updateLauncherRunFile()
    }

    override def runFileJava(default: java.nio.file.Path): java.nio.file.Path = {
      val path = runDir / os.Path(default).last
      coordinator.recordPublishedFile(activeRun, path)
      path.toNIO
    }

    override def close(): Unit = {
      if (closed.compareAndSet(false, true)) {
        coordinator.deactivate(activeRun)
        try mill.api.BuildCtx.withFilesystemCheckerDisabled {
            os.remove(launcherRunFile)
          }
        catch { case _: Throwable => }
        coordinator.cleanupOldRunDirs()
      }
    }

    override def acquireLock(resource0: Resource): ResourceLease =
      if (noBuildLock) {
        publishRun()
        new ResourceLease {
          override def resource: Resource = resource0
          override def kind: LockKind = resource0.kind
          override def downgradeToRead(): ResourceLease = this
          override def close(): Unit = ()
        }
      } else {
        acquire(resource0)
        publishRun()
        new InProcessResourceLease(resource0)
      }

    override def acquireLocks(resources: Seq[Resource]): Lease =
      if (noBuildLock || resources.isEmpty) {
        publishRun()
        () => ()
      } else {
        val distinct = resources.distinct
        val duplicateKinds = distinct
          .groupBy(_.key)
          .collect { case (key, grouped) if grouped.map(_.kind).distinct.size > 1 => key }
        require(
          duplicateKinds.isEmpty,
          s"Cannot acquire mixed read/write locks for the same resource in one batch: ${duplicateKinds.toSeq.sorted.mkString(", ")}"
        )
        val sorted = distinct.sortBy(r => (r.key, r.kind.toString))
        val acquired = scala.collection.mutable.Buffer.empty[ResourceLease]
        try {
          sorted.foreach { resource =>
            acquired += acquireLock(resource)
          }
          () => acquired.reverseIterator.foreach(_.close())
        } catch {
          case e: Throwable =>
            acquired.reverseIterator.foreach { lease =>
              try lease.close()
              catch { case _: Throwable => }
            }
            throw e
        }
      }

    private class InProcessResourceLease(override val resource: Resource) extends ResourceLease {
      private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
      @volatile private var currentKind: LockKind = resource.kind
      override def kind: LockKind = currentKind

      override def downgradeToRead(): ResourceLease = {
        if (currentKind == LockKind.Write && !closed.get()) {
          // Downgrade: release write permits minus 1 (keeping 1 read permit)
          downgradeOwner(resource, owner)
          downgradeOwnedResource(resource)
          semaphore(resource).release(maxPermits - 1)
          currentKind = LockKind.Read
        }
        this
      }

      override def close(): Unit =
        if (closed.compareAndSet(false, true)) {
          unregisterOwner(resource, owner, currentKind)
          unregisterOwnedResource(resource, currentKind)
          val permits = currentKind match {
            case LockKind.Read => 1
            case LockKind.Write => maxPermits
          }
          semaphore(resource).release(permits)
        }
    }

    private def semaphore(resource: Resource): Semaphore =
      lockTable.computeIfAbsent(resource.key, _ => new Semaphore(maxPermits, true))

    private def acquire(resource: Resource): Unit = {
      val sem = semaphore(resource)
      val permits = resource.kind match {
        case LockKind.Read => 1
        case LockKind.Write => maxPermits
      }
      def waitMessage(action: String): Unit = {
        val blocker = blockingOwner(resource)
        val command = blocker.map(_.command).getOrElse("<unknown>")
        val pid = blocker.map(_.pid.toString).getOrElse("<unknown>")
        waitingErr.println(
          s"Another Mill command in the current daemon is running '$command' with PID $pid, $action " +
            s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
        )
      }
      val acquired =
        if (noWaitForBuildLock) sem.tryAcquire(permits)
        else if (sem.tryAcquire(permits)) true
        else {
          waitMessage("waiting for it to be done...")
          sem.acquire(permits)
          true
        }

      if (!acquired) {
        val blocker = blockingOwner(resource)
        val command = blocker.map(_.command).getOrElse("<unknown>")
        val pid = blocker.map(_.pid.toString).getOrElse("<unknown>")
        throw new Exception(
          s"Another Mill command in the current daemon is running '$command' with PID $pid and using resource '${resource.key}', failing"
        )
      }

      registerOwner(resource, owner)
      registerOwnedResource(resource)
    }

  }
}
