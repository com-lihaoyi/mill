package mill.api.daemon

import mill.constants.{DaemonFiles, OutFiles}

import java.io.PrintStream
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

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

  def globalFileResource(path: os.Path, kind: LockKind): Resource =
    Resource(s"global:$path", kind)

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
      activeFile: os.Path,
      var active: Boolean = true,
      var published: Boolean = false,
      publishedFiles: scala.collection.mutable.Map[String, os.Path] =
        scala.collection.mutable.LinkedHashMap.empty
  )

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
        if (!os.exists(out)) return
        lock.synchronized {
          val activeRunDirs = runs.valuesIterator.filter(_.active).map(_.runDir).toSet

          val runRootDir = out / runRootDirName
          val runDirs =
            if (os.exists(runRootDir)) os.list(runRootDir).filter(os.isDir(_)).sortBy(_.last)
            else Seq.empty

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
      } catch {
        case _: Throwable => // best-effort cleanup
      }
    }

    private def refreshWellKnownLinks(): Unit = {
      val latestRunOpt = runs.valuesIterator.filter(_.published).toSeq.lastOption
      val latestActiveRunOpt =
        runs.valuesIterator.filter(r => r.active && r.published).toSeq.lastOption

      updateSymlink(out / DaemonFiles.millConsoleTail, latestRunOpt.map(_.consoleTail))
      updateSymlink(out / OutFiles.millActive, latestActiveRunOpt.map(_.activeFile))
      val publishedNames = runs.valuesIterator.flatMap(_.publishedFiles.keys).toSet
      publishedNames.foreach { fileName =>
        updateSymlink(out / fileName, latestRunOpt.flatMap(_.publishedFiles.get(fileName)))
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

  private def quoteJson(s: String): String =
    "\"" + s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""

  private def readActiveCommand(out: os.Path): Option[String] = {
    try {
      val json = os.read(out / OutFiles.millActive)
      """"command"\s*:\s*"([^"]*)"""".r.findFirstMatchIn(json).map(_.group(1))
    } catch {
      case _: Throwable => None
    }
  }

  final class InProcessManager(
      out: os.Path,
      daemonDir: os.Path,
      activeCommandMessage: String,
      waitingErr: PrintStream,
      override val noBuildLock: Boolean,
      override val noWaitForBuildLock: Boolean
  ) extends Manager {
    override val runId: String =
      s"${System.currentTimeMillis()}-${nextTiebreaker.getAndIncrement()}"

    private val runRootDir: os.Path = out / runRootDirName
    private val runDir: os.Path = runRootDir / runId
    os.makeDir.all(runDir)

    val consoleTail: os.Path = runDir / "mill-console-tail"
    override def consoleTailJava: java.nio.file.Path = consoleTail.toNIO
    private val activeFile: os.Path = runDir / OutFiles.millActive

    private val activeRun = ActiveRun(runId, runDir, consoleTail, activeFile)
    private val coordinator = coordinatorFor(out)

    os.write.over(
      activeFile,
      s"""{"command":${quoteJson(activeCommandMessage)},"processDir":${quoteJson(
          daemonDir.toString
        )},"pid":${ProcessHandle.current().pid()},"runId":${quoteJson(runId)}}"""
    )

    coordinator.register(activeRun)
    coordinator.cleanupOldRunDirs()

    private def publishRun(): Unit = coordinator.publish(activeRun)

    override def runFileJava(default: java.nio.file.Path): java.nio.file.Path = {
      val path = runDir / os.Path(default).last
      coordinator.recordPublishedFile(activeRun, path)
      path.toNIO
    }

    override def close(): Unit = {
      coordinator.deactivate(activeRun)
      coordinator.cleanupOldRunDirs()
    }

    override def acquireLock(resource0: Resource): ResourceLease =
      if (noBuildLock) new ResourceLease {
        override def resource: Resource = resource0
        override def kind: LockKind = resource0.kind
        override def downgradeToRead(): ResourceLease = this
        override def close(): Unit = ()
      }
      else {
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
        sorted.foreach { resource =>
          acquired += acquireLock(resource)
        }
        () => acquired.reverseIterator.foreach(_.close())
      }

    private class InProcessResourceLease(override val resource: Resource) extends ResourceLease {
      private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
      @volatile private var currentKind: LockKind = resource.kind
      override def kind: LockKind = currentKind

      override def downgradeToRead(): ResourceLease = {
        if (currentKind == LockKind.Write && !closed.get()) {
          // Downgrade: release write permits minus 1 (keeping 1 read permit)
          semaphore(resource).release(maxPermits - 1)
          currentKind = LockKind.Read
        }
        this
      }

      override def close(): Unit =
        if (closed.compareAndSet(false, true)) {
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
        val command = readActiveCommand(out).getOrElse(activeCommandMessage)
        waitingErr.println(
          s"Another Mill command in the current daemon is running '$command', $action " +
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
        val command = readActiveCommand(out).getOrElse(activeCommandMessage)
        throw new Exception(
          s"Another Mill command in the current daemon is running '$command' and using resource '${resource.key}', failing"
        )
      }
    }

  }
}
