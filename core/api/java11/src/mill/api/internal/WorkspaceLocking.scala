package mill.api.internal

import mill.constants.DaemonFiles

import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private[mill] object WorkspaceLocking {
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
    def consoleTail: os.Path

    /** Returns a per-run path for well-known out/ artifacts in daemon mode. */
    def runFile(default: os.Path): os.Path = default

    def acquireLock(resource: Resource): ResourceLease
    def acquireLocks(resources: Seq[Resource]): Lease
    def withLocks[T](resources: Seq[Resource])(t: => T): T = {
      val lease = acquireLocks(resources)
      try t
      finally lease.close()
    }
  }

  def globalFileResource(path: os.Path, kind: LockKind): Resource =
    Resource(s"global:${path.toNIO.toAbsolutePath.normalize()}", kind)

  def metaBuildResource(kind: LockKind): Resource =
    Resource("meta-build", kind)

  object NoopManager extends Manager {
    override def runId: String = "noop"
    override def consoleTail: os.Path = os.pwd / "out" / "mill-console-tail"
    override def acquireLock(resource0: Resource): ResourceLease = new ResourceLease {
      override def resource: Resource = resource0
      override def kind: LockKind = resource0.kind
      override def downgradeToRead(): ResourceLease = this
      override def close(): Unit = ()
    }
    override def acquireLocks(resources: Seq[Resource]): Lease = () => ()
    override def close(): Unit = ()
  }

  final class InProcessManager(
      out: os.Path,
      daemonDir: os.Path,
      activeCommandMessage: String,
      launcherPid: Long,
      waitingErr: PrintStream,
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String =
      s"${System.currentTimeMillis()}-${WorkpaceLockingUtils.nextTiebreaker.getAndIncrement()}"

    private val runDir: os.Path = out / WorkpaceLockingUtils.runRootDirName / runId
    os.makeDir.all(runDir)
    private val launcherRunFile = daemonDir / os.RelPath(DaemonFiles.launcherRun(runId))
    private val closed = new AtomicBoolean(false)
    private val activeLeases = scala.collection.mutable.Set.empty[InProcessResourceLease]

    val consoleTail: os.Path = runDir / "mill-console-tail"
    private val owner = WorkpaceLockingUtils.LockOwner(runId, launcherPid, activeCommandMessage)

    private val activeRun = WorkpaceLockingUtils.ActiveRun(runId, runDir, consoleTail)
    private val coordinator = WorkpaceLockingUtils.coordinatorFor(out)

    coordinator.register(activeRun)
    coordinator.cleanupOldRunDirs()
    writeLauncherRunFile()

    private def ensureOpen(): Unit =
      if (closed.get()) throw new IllegalStateException(s"Lock manager $runId is closed")

    private def publishRun(): Unit = coordinator.publish(activeRun)
    private def publishRunIfOpen(): Unit = activeLeases.synchronized {
      ensureOpen()
      publishRun()
    }

    private def writeLauncherRunFile(): Unit =
      if (!closed.get()) {
        val commandJson = ujson.write(ujson.Str(owner.command))
        val json = s"""{"pid":${owner.pid},"command":$commandJson}"""
        try {
          mill.api.BuildCtx.withFilesystemCheckerDisabled {
            os.makeDir.all(launcherRunFile / os.up)
            os.write.over(launcherRunFile, json)
          }
        } catch { case _: Throwable => }
      }

    override def runFile(default: os.Path): os.Path = {
      val link = default
      val target =
        if (link.startsWith(out)) runDir / link.relativeTo(out)
        else runDir / link.last
      os.makeDir.all(target / os.up)
      coordinator.recordPublishedFile(activeRun, link, target)
      target
    }

    override def close(): Unit = {
      var shouldClose = false
      val leases = activeLeases.synchronized {
        shouldClose = closed.compareAndSet(false, true)
        if (shouldClose) activeLeases.toSeq else Nil
      }
      if (shouldClose) {
        leases.foreach(lease =>
          try lease.close()
          catch { case _: Throwable => }
        )
        coordinator.deactivate(activeRun)
        try mill.api.BuildCtx.withFilesystemCheckerDisabled {
            os.remove(launcherRunFile)
          }
        catch { case _: Throwable => }
        coordinator.cleanupOldRunDirs()
      }
    }

    override def acquireLock(resource0: Resource): ResourceLease = {
      ensureOpen()
      if (noBuildLock) {
        publishRunIfOpen()
        NoopManager.acquireLock(resource0)
      } else {
        val state = acquire(resource0)
        val lease = new InProcessResourceLease(resource0, state)
        try {
          activeLeases.synchronized {
            ensureOpen()
            activeLeases += lease
            publishRun()
          }
          lease
        } catch {
          case e: Throwable =>
            try lease.close()
            catch { case _: Throwable => }
            throw e
        }
      }
    }

    override def acquireLocks(resources: Seq[Resource]): Lease =
      if (noBuildLock || resources.isEmpty) {
        publishRunIfOpen()
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
          sorted.foreach(resource => acquired += acquireLock(resource))
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

    private class InProcessResourceLease(
        override val resource: Resource,
        state: WorkpaceLockingUtils.ResourceState
    ) extends ResourceLease {
      private val closed = new AtomicBoolean(false)
      @volatile private var currentKind: LockKind = resource.kind
      override def kind: LockKind = currentKind

      override def downgradeToRead(): ResourceLease = {
        if (currentKind == LockKind.Write && !closed.get()) {
          WorkpaceLockingUtils.downgradeOwner(resource, state, owner)
          state.semaphore.release(WorkpaceLockingUtils.maxPermits - 1)
          currentKind = LockKind.Read
        }
        this
      }

      override def close(): Unit =
        if (closed.compareAndSet(false, true)) {
          WorkpaceLockingUtils.unregisterOwner(resource, state, owner, currentKind)
          val permits = currentKind match {
            case LockKind.Read => 1
            case LockKind.Write => WorkpaceLockingUtils.maxPermits
          }
          state.semaphore.release(permits)
          WorkpaceLockingUtils.releaseResourceState(resource, state)
          activeLeases.synchronized(activeLeases -= this)
        }
    }

    private def acquire(resource: Resource): WorkpaceLockingUtils.ResourceState = {
      val state = WorkpaceLockingUtils.retainResourceState(resource)
      val sem = state.semaphore
      val permits = resource.kind match {
        case LockKind.Read => 1
        case LockKind.Write => WorkpaceLockingUtils.maxPermits
      }
      def blockerDescription: String = {
        val blocker = WorkpaceLockingUtils.blockingOwner(resource)
        val command = blocker.map(_.command).getOrElse("<unknown>")
        val pid = blocker.map(_.pid.toString).getOrElse("<unknown>")
        s"Another Mill command in the current daemon is running '$command' with PID $pid"
      }
      val acquired =
        try sem.tryAcquire(permits, 0L, TimeUnit.MILLISECONDS) || !noWaitForBuildLock && {
            waitingErr.println(
              s"$blockerDescription, waiting for it to be done... " +
                s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
            )
            sem.acquire(permits)
            true
          }
        catch {
          case e: Throwable =>
            WorkpaceLockingUtils.releaseResourceState(resource, state)
            throw e
        }

      if (!acquired) {
        WorkpaceLockingUtils.releaseResourceState(resource, state)
        throw new Exception(
          s"$blockerDescription and using resource '${resource.key}', failing"
        )
      }

      try {
        WorkpaceLockingUtils.registerOwner(resource, state, owner)
        state
      } catch {
        case e: Throwable =>
          sem.release(permits)
          WorkpaceLockingUtils.releaseResourceState(resource, state)
          throw e
      }
    }
  }
}
