package mill.daemon

import mill.api.{BuildCtx, SystemStreams}
import mill.client.lock.Locks
import mill.constants.OutFolderMode
import mill.constants.OutFiles.OutFiles
import mill.server.Server

import scala.concurrent.duration.*
import scala.util.{Failure, Properties, Success, Try}

object MillDaemonMain0 {
  case class Args(
      daemonDir: os.Path,
      outMode: OutFolderMode,
      useFileLocks: Boolean,
      rest: Seq[String]
  )
  object Args {
    def apply(appName: String, args: Array[String]): Either[String, Args] = {
      def usage(extra: String = "") =
        s"usage: $appName <daemon-dir> <out-mode> <use-file-locks> <mill-args>$extra"

      args match {
        case Array(daemonDir, outModeStr, useFileLocksStr, rest*) =>
          Try(OutFolderMode.fromString(outModeStr)) match {
            case Failure(_) =>
              val possibleValues = OutFolderMode.values.map(_.asString).mkString(", ")
              Left(usage(s"\n\n<out-mode> must be one of $possibleValues but was '$outModeStr'"))
            case Success(outMode) =>
              val useFileLocks = useFileLocksStr.toBoolean
              Right(apply(os.Path(daemonDir), outMode, useFileLocks, rest))
          }
        case _ => Left(usage())
      }
    }
  }

  /**
   * Thread-local holding the current daemon's worker cache queue.
   * Used by `MillBuildBootstrap` to register worker caches with the daemon
   * so they can be closed on daemon shutdown even if the evaluation is interrupted.
   */
  val currentDaemonWorkerCaches =
    new scala.util.DynamicVariable[Option[java.util.concurrent.ConcurrentLinkedQueue[scala.collection.mutable.Map[String, (Int, mill.api.Val, mill.api.daemon.internal.TaskApi[?])]]]](None)

  /**
   * Registers a worker cache with the current daemon instance (if any).
   * Called by `MillBuildBootstrap.makeEvaluator0()`.
   */
  def registerWorkerCache(
      cache: scala.collection.mutable.Map[String, (Int, mill.api.Val, mill.api.daemon.internal.TaskApi[?])]
  ): Unit = {
    currentDaemonWorkerCaches.value.foreach(_.add(cache))
  }

  type WorkerCache = scala.collection.mutable.Map[String, (Int, mill.api.Val, mill.api.daemon.internal.TaskApi[?])]
  type WorkerCacheQueue = java.util.concurrent.ConcurrentLinkedQueue[WorkerCache]

  /**
   * Runs a block of code with worker cache tracking enabled.
   * All worker caches created during evaluation are registered and closed in the finally block.
   * Used by both daemon and non-daemon modes to ensure workers are properly cleaned up.
   *
   * @param body The code to run
   * @param extraCleanup Optional additional cleanup to run after closing worker caches
   * @return The result of the body
   */
  def withWorkerTracking[T](body: => T)(extraCleanup: => Unit = ()): T = {
    val workerCaches = new java.util.concurrent.ConcurrentLinkedQueue[WorkerCache]()
    currentDaemonWorkerCaches.withValue(Some(workerCaches)) {
      try body
      finally {
        closeWorkerCaches(workerCaches)
        extraCleanup
      }
    }
  }

  /**
   * Closes all workers in the given queue of worker caches.
   */
  def closeWorkerCaches(caches: WorkerCacheQueue): Unit = {
    val iterator = caches.iterator()
    while (iterator.hasNext) {
      val cache = iterator.next()
      closeWorkersInCache(cache)
    }
  }

  private def closeWorkersInCache(cache: WorkerCache): Unit = {
    val cacheSnapshot = cache.synchronized { cache.toMap }
    if (cacheSnapshot.nonEmpty) {
      val deps = mill.exec.GroupExecution.workerDependencies(cacheSnapshot)
      val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
      val allWorkers = cacheSnapshot.values.map(_._3).toSet
      mill.exec.GroupExecution.closeWorkersInReverseTopologicalOrder(
        allWorkers,
        cache,
        topoIndex,
        closeable =>
          try closeable.close()
          catch { case scala.util.control.NonFatal(_) => }
      )
    }
  }

  def main(args0: Array[String]): Unit = {
    // Set by an integration test
    if (System.getenv("MILL_DAEMON_CRASH") == "true")
      sys.error("Mill daemon early crash requested")

    val args =
      Args(getClass.getName, args0).fold(err => throw IllegalArgumentException(err), identity)

    // temporarily disabling FFM use by coursier, which has issues with the way
    // Mill manages class loaders, throwing things like
    // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
    if (Properties.isWin) sys.props("coursier.windows.disable-ffm") = "true"

    coursier.Resolve.proxySetup() // Take into account proxy-related Java properties

    mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
      Server.overrideSigIntHandling()

      val acceptTimeout =
        Try(System.getProperty("mill.server_timeout").toInt.millis).getOrElse(30.minutes)

      val exitCode = new MillDaemonMain0(
        daemonDir = args.daemonDir,
        acceptTimeout = acceptTimeout,
        Locks.forDirectory(args.daemonDir.toString, args.useFileLocks),
        outMode = args.outMode
      ).run().getOrElse(0)

      System.exit(exitCode)
    }
  }
}

class MillDaemonMain0(
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    outMode: OutFolderMode
) extends mill.server.MillDaemonServer[RunnerState](
      daemonDir,
      acceptTimeout,
      locks
    ) {

  def initialStateCache = RunnerState.empty

  /**
   * Tracks worker caches from active evaluations. Workers may exist in these caches
   * even if the evaluation was interrupted before the state could be saved.
   */
  private val activeWorkerCaches: MillDaemonMain0.WorkerCacheQueue =
    new java.util.concurrent.ConcurrentLinkedQueue()

  override def run(): Option[Int] = {
    // Set up worker cache tracking for this daemon instance
    MillDaemonMain0.currentDaemonWorkerCaches.withValue(Some(activeWorkerCaches)) {
      try super.run()
      finally {
        // Close workers from active (possibly interrupted) evaluations
        MillDaemonMain0.closeWorkerCaches(activeWorkerCaches)
        // Also close any workers in the saved state (for completeness)
        getStateCache.closeAllWorkers()
      }
    }
  }

  val outFolder: os.Path = os.Path(OutFiles.outFor(outMode), BuildCtx.workspaceRoot)

  val outLock = MillMain0.doubleLock(outFolder)

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Server.StopServer,
      serverToClient: mill.rpc.MillRpcChannel[mill.launcher.DaemonRpc.ServerToClient]
  ): (Boolean, RunnerState) = {
    // Create runner that sends subprocess requests to the launcher via RPC
    val launcherRunner: mill.api.daemon.LauncherSubprocess.Runner =
      config =>
        serverToClient(mill.launcher.DaemonRpc.ServerToClient.RunSubprocess(config)).exitCode

    try MillMain0.main0(
        args = args,
        stateCache = stateCache,
        mainInteractive = mainInteractive,
        streams0 = streams,
        env = env,
        setIdle = setIdle,
        userSpecifiedProperties0 = userSpecifiedProperties,
        initialSystemProperties = initialSystemProperties,
        systemExit = systemExit,
        daemonDir = daemonDir,
        outLock = outLock,
        launcherSubprocessRunner = launcherRunner
      )
    catch {
      // Let InterruptedException propagate without printing (used by deferredStopServer for shutdown)
      case e: InterruptedException => throw e
      case e if MillMain0.handleMillException(streams.err, stateCache).isDefinedAt(e) =>
        MillMain0.handleMillException(streams.err, stateCache)(e)
    }
  }
}
