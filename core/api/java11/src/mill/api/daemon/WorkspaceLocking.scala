package mill.api.daemon

import mill.constants.{DaemonFiles, OutFiles}

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

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

    /** Returns a per-run profile path to avoid corruption under concurrent daemon runs. */
    def profilePathJava(default: java.nio.file.Path): java.nio.file.Path = default
    /** Returns a per-run chrome profile path to avoid corruption under concurrent daemon runs. */
    def chromeProfilePathJava(default: java.nio.file.Path): java.nio.file.Path = default

    def acquireLock(resource: Resource): ResourceLease
    def acquireLocks(resources: Seq[Resource]): Lease
    def withLocks[T](resources: Seq[Resource])(t: => T): T = {
      val lease = acquireLocks(resources)
      try t
      finally lease.close()
    }
    def acquireMetaBuildRead(depth: Int): Lease =
      acquireLocks(Seq(Resource(s"meta-build:$depth", LockKind.Read)))
    def withMetaBuildRead[T](depth: Int)(t: => T): T =
      withLocks(Seq(Resource(s"meta-build:$depth", LockKind.Read)))(t)
    def withMetaBuildWrite[T](depth: Int)(t: => T): T =
      withLocks(Seq(Resource(s"meta-build:$depth", LockKind.Write)))(t)
  }

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

  // Never evicted; bounded by the number of distinct lock keys (tasks + meta-build depths)
  private val lockTable = new ConcurrentHashMap[String, ReentrantReadWriteLock]()

  private val runDirPrefix = "mill-run-"

  /** Maximum number of per-run directories to retain for debugging. */
  private val maxRetainedRuns = 10

  private case class ActiveRun(
      runId: String,
      runDir: os.Path,
      consoleTail: os.Path,
      activeFile: os.Path,
      var published: Boolean = false,
      var profilePathOpt: Option[os.Path] = None,
      var chromeProfilePathOpt: Option[os.Path] = None
  )

  private val activeRunsLock = new Object
  private val activeRuns =
    scala.collection.mutable.Map.empty[String, scala.collection.mutable.LinkedHashMap[String, ActiveRun]]

  /**
   * Clean up old per-run directories in `out`, keeping the most recent [[maxRetainedRuns]].
   * Directories are named `mill-run-{timestamp}-{counter}` and sort chronologically.
   */
  private def cleanupOldRunDirs(out: os.Path): Unit = {
    try {
      if (!os.exists(out)) return
      activeRunsLock.synchronized {
        val activeRunDirs = activeRuns
          .getOrElse(out.toString, scala.collection.mutable.LinkedHashMap.empty)
          .valuesIterator
          .map(_.runDir)
          .toSet

        val runDirs = os.list(out)
          .filter(p => os.isDir(p) && p.last.startsWith(runDirPrefix))
          .sortBy(_.last)

        val removable = runDirs.filterNot(activeRunDirs)
        val toRemove =
          removable.take(math.min(removable.size, math.max(0, runDirs.size - maxRetainedRuns)))
        toRemove.foreach { dir =>
          try os.remove.all(dir)
          catch { case _: Throwable => }
        }
      }
    } catch {
      case _: Throwable => // best-effort cleanup
    }
  }

  /** Atomically replace `link` with a relative symlink pointing to `target`. */
  private def updateSymlink(link: os.Path, targetOpt: Option[os.Path]): Unit = {
    try {
      try os.remove.all(link)
      catch { case _: Throwable => }

      targetOpt.foreach { target =>
        val rel = target.relativeTo(link / os.up)
        os.symlink(link, rel)
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

  private def refreshWellKnownLinks(out: os.Path): Unit = activeRunsLock.synchronized {
    val latestRunOpt = activeRuns
      .get(out.toString)
      .flatMap(_.valuesIterator.filter(_.published).toSeq.lastOption)

    updateSymlink(out / DaemonFiles.millConsoleTail, latestRunOpt.map(_.consoleTail))
    updateSymlink(out / OutFiles.millActive, latestRunOpt.map(_.activeFile))
    updateSymlink(out / OutFiles.millProfile, latestRunOpt.flatMap(_.profilePathOpt))
    updateSymlink(out / OutFiles.millChromeProfile, latestRunOpt.flatMap(_.chromeProfilePathOpt))
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

    private val runDir: os.Path = out / s"$runDirPrefix$runId"
    os.makeDir.all(runDir)

    val consoleTail: os.Path = runDir / "mill-console-tail"
    override def consoleTailJava: java.nio.file.Path = consoleTail.toNIO
    private val activeFile: os.Path = runDir / OutFiles.millActive

    private val activeRun = ActiveRun(runId, runDir, consoleTail, activeFile)

    os.write.over(
      activeFile,
      s"""{"command":${quoteJson(activeCommandMessage)},"processDir":${quoteJson(daemonDir.toString)},"pid":${ProcessHandle.current().pid()},"runId":${quoteJson(runId)}}"""
    )

    activeRunsLock.synchronized {
      val runsForOut =
        activeRuns.getOrElseUpdate(out.toString, scala.collection.mutable.LinkedHashMap.empty)
      runsForOut.update(runId, activeRun)
    }
    cleanupOldRunDirs(out)

    private def publishRun(): Unit = activeRunsLock.synchronized {
      if (!activeRun.published) {
        activeRun.published = true
        refreshWellKnownLinks(out)
      }
    }

    override def profilePathJava(default: java.nio.file.Path): java.nio.file.Path = {
      val path = runDir / os.Path(default).last
      activeRunsLock.synchronized {
        activeRun.profilePathOpt = Some(path)
        refreshWellKnownLinks(out)
      }
      path.toNIO
    }

    override def chromeProfilePathJava(default: java.nio.file.Path): java.nio.file.Path = {
      val path = runDir / os.Path(default).last
      activeRunsLock.synchronized {
        activeRun.chromeProfilePathOpt = Some(path)
        refreshWellKnownLinks(out)
      }
      path.toNIO
    }

    override def close(): Unit = {
      activeRunsLock.synchronized {
        activeRuns.get(out.toString).foreach { runsForOut =>
          runsForOut.remove(runId)
          if (runsForOut.isEmpty) activeRuns.remove(out.toString)
        }
        refreshWellKnownLinks(out)
      }
      cleanupOldRunDirs(out)
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
        InProcessResourceLease(resource0)
      }

    override def acquireLocks(resources: Seq[Resource]): Lease =
      if (noBuildLock || resources.isEmpty) {
        publishRun()
        () => ()
      }
      else {
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

    private case class InProcessResourceLease(
        override val resource: Resource
    ) extends ResourceLease {
      private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
      override def kind: LockKind = resource.kind

      override def downgradeToRead(): ResourceLease =
        if (kind == LockKind.Read || closed.get()) this
        else {
          val lock = rwLock(resource)
          lock.readLock().lock()
          lock.writeLock().unlock()
          closed.set(true)
          InProcessResourceLease(resource.copy(kind = LockKind.Read))
        }

      override def close(): Unit =
        if (closed.compareAndSet(false, true)) release(resource)
    }

    private def rwLock(resource: Resource): ReentrantReadWriteLock =
      lockTable.computeIfAbsent(resource.key, _ => new ReentrantReadWriteLock(true))

    private def acquire(resource: Resource): Unit = {
      val lock = rwLock(resource)
      def waitMessage(action: String): Unit = {
        val command = readActiveCommand(out).getOrElse(activeCommandMessage)
        waitingErr.println(
          s"Another Mill command in the current daemon is running '$command', $action " +
            s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"
        )
      }
      val acquired = resource.kind match {
        case LockKind.Read =>
          if (noWaitForBuildLock) lock.readLock().tryLock()
          else if (lock.readLock().tryLock()) true
          else {
            waitMessage("waiting for it to be done...")
            lock.readLock().lock()
            true
          }
        case LockKind.Write =>
          if (noWaitForBuildLock) lock.writeLock().tryLock()
          else if (lock.writeLock().tryLock()) true
          else {
            waitMessage("waiting for it to be done...")
            lock.writeLock().lock()
            true
          }
      }

      if (!acquired) {
        val command = readActiveCommand(out).getOrElse(activeCommandMessage)
        throw new Exception(
          s"Another Mill command in the current daemon is running '$command' and using resource '${resource.key}', failing"
        )
      }
    }

    private def release(resource: Resource): Unit = {
      val lock = rwLock(resource)
      resource.kind match {
        case LockKind.Read => lock.readLock().unlock()
        case LockKind.Write => lock.writeLock().unlock()
      }
    }
  }
}
