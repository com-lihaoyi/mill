package mill.bsp.worker

import ch.epfl.scala.bsp4j.*
import mill.api.*
import mill.bsp.worker.Utils.groupList
import mill.client.lock.Lock
import mill.api.internal.WatchSig
import mill.internal.PrefixLogger
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.{
  CompletableFuture,
  ConcurrentHashMap,
  Executors,
  ExecutorService,
  Semaphore,
  ThreadFactory,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}
import mill.api.daemon.internal.NonFatal
import mill.api.daemon.internal.bsp.{BspBootstrapBridge, BspModuleApi, BspServerResult}
import mill.api.daemon.internal.*
import mill.constants.OutFiles.OutFiles

/**
 * Mill's BSP server implementation.
 *
 * This class contains the server infrastructure (state management, request handling,
 * threading). The actual BSP endpoint implementations are in the MillBspEndpoints trait.
 */
private abstract class MillBuildServer(
    protected val topLevelProjectRoot: os.Path,
    protected val bspVersion: String,
    protected val serverVersion: String,
    protected val serverName: String,
    protected val canReload: Boolean,
    protected val onShutdown: () => Unit,
    protected val baseLogger: Logger,
    out: os.Path,
    sessionProcessPid: Long,
    noWaitForBspLock: Boolean,
    killOther: Boolean,
    bspWatch: Boolean,
    bootstrapBridge: BspBootstrapBridge
) extends EndpointsApi with AutoCloseable {

  import MillBuildServer.*

  // ==========================================================================
  // Session State
  // ==========================================================================

  @volatile protected var client: BuildClient = scala.compiletime.uninitialized
  @volatile protected var sessionInfo: MillBspEndpoints.SessionInfo =
    scala.compiletime.uninitialized

  /**
   * Completes when the BSP client signals end-of-session (`build/exit`) or
   * requests `workspace/reload`. The daemon awaits this future to know when
   * to exit its BSP block.
   */
  protected[worker] val shutdownPromise: Promise[BspServerResult] = Promise[BspServerResult]()

  private val requestCount = new AtomicInteger

  /**
   * Executor for BSP request handling. Each request runs concurrently in its
   * own thread.
   *
   * Why removing the previous serialization barrier is safe under the
   * fresh-bootstrap-per-request design:
   *   1. Each request goes through `bootstrapBridge.runBootstrap`, which calls
   *      `runMillBootstrap`. That returns a request-local `RunnerLauncherState`
   *      with its own freshly-instantiated `EvaluatorImpl` (and its own
   *      `Execution` with its own per-execute0 lease tracker, nesting depth,
   *      mismatch reasons, profile logger).
   *   2. `BspEvaluators` is constructed from those request-local evaluators,
   *      so `extractInputPaths`'s `ev.executeApi(inputTasks)` call operates on
   *      a per-request evaluator instance. No mutable evaluator state is
   *      shared across concurrent requests.
   *   3. The shared resources `executeApi` reaches — `RunnerSharedState` frames,
   *      meta-build classloader, daemon-shared worker caches, per-task locks via
   *      `LauncherLocking` — already have their own concurrency control
   *      (atomic publish, refcounted file lock, writer-preferring rwlock,
   *      `ConcurrentHashMap.compute`) and are designed for concurrent
   *      multi-launcher access — concurrent CLI commands already exercise
   *      these.
   *   4. The watcher thread also runs its own bootstrap with its own evaluator;
   *      it composes with request bootstraps via the same shared-resource
   *      concurrency control.
   *
   * The historical `bootstrapRunLock` was therefore covering a hazard that no
   * longer exists in this design.
   */
  private val bspRequestExecutor: ExecutorService = {
    val counter = new AtomicInteger(0)
    val threadFactory: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, s"mill-bsp-request-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
    Executors.newCachedThreadPool(threadFactory)
  }

  def initialized = sessionInfo != null

  private def withBootstrappedEvaluators[T](
      activeCommandMessage: String
  )(
      onUnavailable: BspBootstrapBridge.BootstrapState => T
  )(
      body: (BspEvaluators, BspBootstrapBridge.BootstrapState) => T
  ): T =
    bootstrapBridge.runBootstrap(
      activeCommandMessage,
      new BspBootstrapBridge.Body[T] {
        override def apply(state: BspBootstrapBridge.BootstrapState): T =
          if (state.errorOpt.isDefined && state.evaluators.isEmpty) onUnavailable(state)
          else {
            val bspEvaluators = new BspEvaluators(
              topLevelProjectRoot,
              state.evaluators.asScala.toSeq,
              s => baseLogger.debug(s()),
              state.watched.asScala.toSeq,
              state.errorOpt
            )
            body(bspEvaluators, state)
          }
      }
    )

  private var bspLock: Lock = scala.compiletime.uninitialized
  private var bspLockLease: mill.client.lock.Locked = scala.compiletime.uninitialized
  private var bspProcessLockLease: MillBuildServer.ProcessLockLease =
    scala.compiletime.uninitialized
  private var activeBspFile: os.Path = scala.compiletime.uninitialized
  private var activeBspLockId: String = scala.compiletime.uninitialized

  private def initLock(bspLockId: String): Unit = {
    assert(bspLock == null)
    assert(bspLockLease == null)
    assert(bspProcessLockLease == null)
    val processLock = MillBuildServer.processLockFor(bspLockId)
    val fileLock = Lock.file((out / OutFiles.millBspLock(bspLockId)).toString)
    val activeBspFile0 = out / OutFiles.millActiveBsp(bspLockId)

    def readActiveInfo(): Option[Long] =
      try {
        val json = os.read(activeBspFile0)
        val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
        pidPattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)
      } catch {
        case NonFatal(_) => None
      }

    def waitForFileLock(): mill.client.lock.TryLocked = {
      while (true) {
        val tryLocked = fileLock.tryLock()
        if (tryLocked.isLocked) return tryLocked
        Thread.sleep(10L)
      }
      throw new IllegalStateException("unreachable")
    }

    def terminateOther(pidOpt: Option[Long]): Unit =
      pidOpt match {
        case Some(pid) if pid == sessionProcessPid =>
          baseLogger.warn(
            s"Active BSP process PID $pid matches the current launcher; waiting for the existing session to stop"
          )
        case Some(pid) =>
          val handleOpt = ProcessHandle.of(pid)
          if (handleOpt.isPresent) {
            val handle = handleOpt.get()
            if (handle.isAlive()) {
              if (handle.destroy())
                baseLogger.info(s"Sent SIGTERM to process $pid")
              else
                baseLogger.warn(s"Could not send SIGTERM to process $pid")
              var i = 200
              while (i > 0 && handle.isAlive()) {
                Thread.sleep(10L)
                i -= 1
              }
              if (handle.isAlive())
                if (handle.destroyForcibly())
                  baseLogger.info(s"Sent SIGKILL to process $pid")
                else
                  baseLogger.warn(s"Could not send SIGKILL to process $pid")
            } else
              baseLogger.info(s"Other Mill process with PID $pid exited")
          } else
            baseLogger.info(s"Other Mill process with PID $pid exited")
        case None =>
          baseLogger.warn(
            s"PID of other Mill process not found in $activeBspFile0, could not terminate it"
          )
      }

    def stopOtherLocalSession(): Boolean =
      MillBuildServer.shutdownActiveSession(bspLockId, this)

    var processLease: MillBuildServer.ProcessLockLease = null
    var fileLease: mill.client.lock.TryLocked = null
    var initialized = false
    try {
      processLease = processLock.tryLock()
      if (processLease == null) {
        if (noWaitForBspLock)
          throw new Exception("Another Mill BSP process is running, failing")

        val pidOpt = readActiveInfo()
        if (killOther) {
          if (stopOtherLocalSession())
            baseLogger.info(s"Asked the active BSP session for '$bspLockId' to shut down")
          else terminateOther(pidOpt)
        } else
          baseLogger.info(
            s"Another Mill BSP server is running with PID ${pidOpt.fold("<unknown>")(_.toString)} waiting for it to be done..."
          )

        processLease = processLock.lock()
      }

      fileLease = fileLock.tryLock()
      if (!fileLease.isLocked) {
        if (noWaitForBspLock)
          throw new Exception("Another Mill BSP process is running, failing")

        val pidOpt = readActiveInfo()
        if (killOther) terminateOther(pidOpt)
        else
          baseLogger.info(
            s"Another Mill BSP server is running with PID ${pidOpt.fold("<unknown>")(_.toString)} waiting for it to be done..."
          )

        fileLease = waitForFileLock()
      }

      bspProcessLockLease = processLease
      bspLock = fileLock
      bspLockLease = fileLease
      activeBspFile = activeBspFile0
      activeBspLockId = bspLockId

      val daemonPid = ProcessHandle.current().pid()
      val json = s"""{"pid":$sessionProcessPid,"serverPid":$daemonPid}"""
      os.write.over(activeBspFile0, json)
      MillBuildServer.registerActiveSession(
        bspLockId,
        MillBuildServer.ActiveSession(this, sessionProcessPid, () => close())
      )
      initialized = true
    } finally {
      if (!initialized) {
        if (fileLease != null)
          try fileLease.close()
          catch { case _: Throwable => () }
        try fileLock.close()
        catch { case _: Throwable => () }
        if (processLease != null)
          try processLease.close()
          catch { case _: Throwable => () }
      }
    }
  }

  protected def doneInitializingBuild(): Unit = {
    assert(initialized, "Expected Mill BSP server to be initialized")
    if (bspLock == null) {
      val bspLockId = sessionInfo.clientDisplayName
        // just in case
        .replace(" ", "_")
        .replace("/", "_")
        .replace("\\", "_")
      initLock(bspLockId)
      if (bspWatch) startWatcherThread()
    } else
      baseLogger.warn("Mill BSP server initialized more than once")
  }

  /**
   * Background thread that pushes `onBuildTargetDidChange` notifications to
   * the connected BSP client whenever any of the previous bootstrap's
   * watched inputs change. Each iteration:
   *
   *   1. Asks the bridge to bootstrap fresh evaluators+watches.
   *   2. Inside the bridge body, computes target snapshots, diffs vs. the
   *      previous iteration's snapshots, sends `buildTargetDidChange` if
   *      they differ, and polls `WatchSig.haveNotChanged` until something
   *      changes (or shutdown is requested).
   *   3. Returns from the body — the bridge tears down the bootstrap state
   *      and releases all read leases — then loops.
   *
   * The polling loop holds the bootstrap's read leases (meta-build read
   * lease + per-task read leases retained from the `resolve _` evaluation)
   * for the duration of one iteration. Concurrent BSP requests and CLI
   * commands run independent bootstraps; they each acquire their own read
   * leases on the same locks, which is compatible. A concurrent CLI command
   * that needs to write the meta-build classloader (e.g. `build.mill`
   * changed) escalates to a write lease, waits for this iteration's read
   * lease to be released — which happens at most one watcher poll interval
   * after the change is detected — then refreshes; the watcher then
   * bootstraps against the post-refresh classloader on its next iteration.
   */
  @volatile private var watcherThread: Thread = null
  private val watcherPollIntervalMs: Long = 500L

  private def startWatcherThread(): Unit = {
    val watchLogger = new PrefixLogger(baseLogger, Seq("watch"))
    watcherThread = mill.api.daemon.StartThread("mill-bsp-watcher", daemon = true) {
      var prevTargetSnapshots = Seq.empty[ChangeNotifier.TargetSnapshot]
      var seenAnyBootstrap = false
      try while (
          !stopped &&
          !shutdownPromise.isCompleted &&
          !Thread.currentThread().isInterrupted
        ) {
          try {
            // Compute snapshots inside the bridge body, then return so the
            // bootstrap's read leases are released before we start polling.
            // Holding leases during the poll would block any concurrent CLI
            // command that needs to acquire a meta-build/task write lease
            // (e.g. when build.mill changed and the command needs to refresh
            // the meta-build classloader), starving it.
            val watchedSeq =
              withBootstrappedEvaluators("BSP:watch")(state => state.watched.asScala.toSeq) {
                (bspEvaluators, state) =>
                  val current = bspEvaluators.targetSnapshots
                  // Re-read `client` each iteration: `onConnectWithClient`
                  // may run after `startWatcherThread`, so capturing once at
                  // thread start would silently leave `client0` null forever
                  // and never deliver `buildTargetDidChange`.
                  val currentClient = client
                  if (seenAnyBootstrap && currentClient != null)
                    ChangeNotifier.notifyChanges(
                      currentClient,
                      prevTargetSnapshots,
                      current
                    )
                  prevTargetSnapshots = current
                  seenAnyBootstrap = true
                  state.watched.asScala.toSeq
              }

            // Poll without holding leases. WatchSig.haveNotChanged on a
            // filesystem-style watch is pure I/O, but Task.Input watches can
            // re-invoke closures whose classes lived in the meta-build
            // classloader; once leases are released a concurrent refresh may
            // close that classloader. Treat any throw from the closure as
            // "something changed" and re-bootstrap on the next iteration.
            def stillUnchanged(): Boolean =
              try watchedSeq.forall(WatchSig.haveNotChanged)
              catch { case NonFatal(_) => false }

            while (
              !stopped &&
              !shutdownPromise.isCompleted &&
              !Thread.currentThread().isInterrupted &&
              stillUnchanged()
            ) {
              try Thread.sleep(watcherPollIntervalMs)
              catch {
                case _: InterruptedException =>
                  // Re-set the interrupt flag so the loop condition above
                  // exits on the next iteration (and so the outer
                  // `while (!stopped && !shutdownPromise.isCompleted)` exits
                  // when its body propagates the InterruptedException).
                  Thread.currentThread().interrupt()
              }
            }
          } catch {
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(ex) =>
              watchLogger.error(s"BSP watcher iteration failed: $ex")
              ex.printStackTrace(watchLogger.streams.err)
              try Thread.sleep(1000L)
              catch {
                case _: InterruptedException =>
                  Thread.currentThread().interrupt()
              }
          }
        }
      catch {
        case _: InterruptedException => ()
      }
    }
  }

  // ==========================================================================
  // Lifecycle Management
  // ==========================================================================

  def close(): Unit = {
    stopped = true
    if (activeBspLockId != null)
      MillBuildServer.unregisterActiveSession(activeBspLockId, this)
    if (activeBspFile != null)
      try {
        val currentPid =
          if (os.exists(activeBspFile)) {
            val json = os.read(activeBspFile)
            val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
            pidPattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)
          } else None
        if (currentPid.contains(sessionProcessPid))
          os.remove(activeBspFile, checkExists = false)
      } catch {
        case NonFatal(_) => ()
      }
    if (bspLockLease != null)
      try bspLockLease.close()
      catch { case _: Throwable => () }
    if (bspLock != null)
      try bspLock.close()
      catch { case _: Throwable => () }
    if (bspProcessLockLease != null)
      try bspProcessLockLease.close()
      catch { case _: Throwable => () }
    bspLockLease = null
    bspLock = null
    bspProcessLockLease = null
    activeBspFile = null
    activeBspLockId = null
    // Settle the shutdown future as Shutdown only if neither
    // [[completeSessionResult]] (called from `onBuildExit` /
    // `workspaceReload`) nor the JSON-RPC listener thread has already settled
    // it. trySuccess is the right primitive here: first writer wins, so a
    // workspaceReload that lands just before close() preserves its
    // ReloadWorkspace result, and an external close that arrives first wins
    // with Shutdown. Using trySuccess (rather than tryFailure) avoids the
    // daemon's BSP block misreporting an intentional close as "BSP server
    // threw an exception".
    shutdownPromise.trySuccess(BspServerResult.Shutdown)
    if (watcherThread != null) watcherThread.interrupt()
    // Reject any newly-submitted requests; let in-flight ones finish.
    bspRequestExecutor.shutdown()
    try {
      if (!bspRequestExecutor.awaitTermination(2, TimeUnit.SECONDS))
        bspRequestExecutor.shutdownNow()
    } catch {
      case _: InterruptedException =>
        bspRequestExecutor.shutdownNow()
        Thread.currentThread().interrupt()
    }
  }

  def onConnectWithClient(buildClient: BuildClient): Unit = client = buildClient

  // ==========================================================================
  // Request Handling Infrastructure
  // ==========================================================================

  protected def handlerTasks[T, V, W](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String,
      originId: String
  )(block: (TaskContext[W], Logger) => T)(
      agg: (java.util.List[T], BspEvaluators, Logger) => V
  )(using name: sourcecode.Name, enclosing: sourcecode.Enclosing): CompletableFuture[V] = {
    val prefix = name.value
    handlerEvaluators() { (state, logger) =>
      val ids = state.filterNonSynthetic(targetIds(state).asJava).asScala
      val tasksSeq = ids.flatMap { id =>
        state.bspModulesById.get(id).flatMap { (m, ev) =>
          tasks.lift.apply(m).map(ts => (ts, (ev, id, m)))
        }
      }

      val groups0 = groupList(tasksSeq)(_._2._1) {
        case (tasks, (_, id, m)) => (id, m, tasks)
      }

      val evaluated = groups0.flatMap { case (ev, targetIdTasks) =>
        val requestDescription0 = requestDescription.replace(
          "{}",
          targetIdTasks.map(_._2.bspDisplayName).mkString(", ")
        )
        val results = evaluate(
          ev,
          requestDescription0,
          targetIdTasks.map(_._3),
          logger = logger,
          reporter = Utils.getBspLoggedReporterPool(originId, state.bspIdByModule, client)
        )
        val resultsById = targetIdTasks.flatMap { case (id, m, task) =>
          results.transitiveResultsApi(task)
            .asSuccess
            .map(_.value.value.asInstanceOf[W])
            .map((id, m, _))
        }

        def logError(id: BuildTargetIdentifier, errorMsg: String): Unit = {
          val msg = s"Request '$prefix' failed for ${id.getUri}: ${errorMsg}"
          logger.error(msg)
          client.onBuildLogMessage(new LogMessageParams(MessageType.ERROR, msg))
        }

        resultsById.flatMap { case (id, m, values) =>
          try Seq(block(new TaskContext(id, m, values, ev, state), logger))
          catch {
            case NonFatal(e) =>
              logError(id, e.toString)
              Seq()
          }
        }
      }

      agg(evaluated.asJava, state, logger)
    }
  }

  @volatile private var stopped = false

  /**
   * Signals end-of-session to the daemon's BSP block. Called from the
   * client-driven JSON-RPC handlers `onBuildExit` ([[BspServerResult.Shutdown]])
   * and `workspaceReload` ([[BspServerResult.ReloadWorkspace]]).
   */
  protected def completeSessionResult(result: BspServerResult): Unit =
    shutdownPromise.trySuccess(result)

  protected def handlerEvaluators[V](
      checkInitialized: Boolean = true
  )(block: (BspEvaluators, Logger) => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val prefix = name.value
    val logger = createLogger()
    val future = new CompletableFuture[V]

    if (checkInitialized && !initialized) {
      val msg = s"Can not respond to $prefix request before receiving the `initialize` request."
      logger.error(msg)
      future.completeExceptionally(new Exception(msg))
    } else if (stopped) {
      future.completeExceptionally(new java.util.concurrent.CancellationException(
        s"BSP server is shutting down; rejecting request $prefix"
      ))
    } else {
      // Submit each request to the BSP request executor so multiple requests
      // can bootstrap and evaluate concurrently. Mill's per-task and per-meta-
      // build locks keep concurrent requests safe; metadata-only requests can
      // proceed in parallel with a long-running compile request.
      try bspRequestExecutor.execute(() => runRequest(prefix, logger, future, block))
      catch {
        case _: java.util.concurrent.RejectedExecutionException =>
          future.completeExceptionally(new java.util.concurrent.CancellationException(
            s"BSP server is shutting down; rejecting request $prefix"
          ))
      }
    }
    future
  }

  private def runRequest[V](
      prefix: String,
      logger: Logger,
      future: CompletableFuture[V],
      block: (BspEvaluators, Logger) => V
  ): Unit = {
    // Fast path: if the future was cancelled before this request reached the
    // executor (lsp4j's `$/cancelRequest` got delivered before we picked it
    // up), skip the bootstrap entirely.
    if (future.isCancelled()) {
      logger.info(s"$prefix was cancelled")
      return
    }
    try {
      withBootstrappedEvaluators(s"BSP:$prefix") { state =>
        val error = state.errorOpt.get
        logger.error(error)
        future.completeExceptionally(new IllegalStateException(error))
      } { (bspEvaluators, _) =>
        if (future.isCancelled()) {
          logger.info(s"$prefix was cancelled")
        } else {
          executeWithTiming(prefix, logger, future)(block(bspEvaluators, logger))
        }
      }
    } catch {
      case t: Throwable =>
        logger.error(s"Could not process request: $t")
        t.printStackTrace(logger.streams.err)
        future.completeExceptionally(t)
    }
  }

  /** Executes a block with timing/logging and completes the given future */
  private def executeWithTiming[V](prefix: String, logger: Logger, future: CompletableFuture[V])(
      block: => V
  ): Unit = {
    val start = System.currentTimeMillis()
    baseLogger.prompt.beginChromeProfileEntry(prefix)
    logger.info(s"Entered $prefix")

    val result = NonFatal.Try(block)

    baseLogger.prompt.endChromeProfileEntry()
    logger.info(s"$prefix took ${System.currentTimeMillis() - start} msec")

    result match {
      case Success(v) =>
        logger.debug(s"$prefix result: $v")
        future.complete(v)
      case Failure(e) =>
        logger.error(s"$prefix caught exception: $e")
        e.printStackTrace(logger.streams.err)
        future.completeExceptionally(e)
    }
  }

  protected def handlerRaw[V](block: Logger => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val logger = createLogger()
    val future = new CompletableFuture[V]
    executeWithTiming(name.value, logger, future)(block(logger))
    future
  }

  protected def createLogger()(using enclosing: sourcecode.Enclosing): Logger = {
    val requestCount0 = requestCount.incrementAndGet()
    val name = enclosingRequestName
    new BspLogger(
      client,
      requestCount0,
      new PrefixLogger(
        new ProxyLogger(baseLogger) {
          override def logKey: Seq[String] = {
            val logKey0 = super.logKey
            if (logKey0.startsWith(Seq("bsp"))) logKey0.drop(1)
            else logKey0
          }
        },
        Seq(requestCount0.toString, name)
      )
    )
  }

  // ==========================================================================
  // Internal Helpers
  // ==========================================================================

  protected def evaluatorErrorOpt(result: EvaluatorApi.Result[Any]): Option[String] =
    result.values.toEither.left.toOption

  protected def evaluate(
      evaluator: EvaluatorApi,
      requestDescription: String,
      goals: Seq[TaskApi[?]],
      logger: Logger,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      errorOpt: EvaluatorApi.Result[Any] => Option[String] = evaluatorErrorOpt
  ): ExecutionResultsApi = {
    val goalCount = goals.length
    logger.info(s"Evaluating $goalCount ${if (goalCount > 1) "tasks" else "task"}")
    val result = evaluator.executeApi(
      goals,
      reporter,
      testReporter,
      logger,
      serialCommandExec = false
    )
    errorOpt(result) match {
      case None =>
        logger.info("Done")
      case Some(error) =>
        logger.warn(error)
        logger.info("Failed")
        client.onBuildLogMessage(new LogMessageParams(MessageType.WARNING, error))
    }
    result.executionResults
  }

  // ==========================================================================
  // Test Endpoints
  // ==========================================================================

  @JsonRequest("millTest/loggingTest")
  def loggingTest(): CompletableFuture[Object] = {
    handlerEvaluators() { (state, logger) =>
      val tasksEvs = state.bspModulesIdList
        .collectFirst {
          case (_, (m: JavaModuleApi, ev)) =>
            Seq(((m, m.bspJavaModule().bspLoggingTest), ev))
        }
        .getOrElse {
          sys.error("No BSP build target available")
        }

      tasksEvs
        .groupMap(_._2)(_._1)
        .map { case (ev, ts) =>
          evaluate(
            ev,
            s"Checking logging for ${ts.map(_._1.bspDisplayName).mkString(", ")}",
            ts.map(_._2),
            logger,
            reporter = Utils.getBspLoggedReporterPool("", state.bspIdByModule, client)
          )
        }
        .toSeq
      null
    }
  }
}

private object MillBuildServer {
  private final case class ActiveSession(
      owner: MillBuildServer,
      processPid: Long,
      shutdown: () => Unit
  )

  private final class ProcessLock(private val semaphore: Semaphore) {
    def lock(): ProcessLockLease = {
      semaphore.acquire()
      new ProcessLockLease(semaphore)
    }
    def tryLock(): ProcessLockLease =
      if (semaphore.tryAcquire()) new ProcessLockLease(semaphore)
      else null
  }

  private final class ProcessLockLease(private val semaphore: Semaphore) extends AutoCloseable {
    private var released = false

    override def close(): Unit = synchronized {
      if (!released) {
        released = true
        semaphore.release()
      }
    }
  }

  private val processLocks = new ConcurrentHashMap[String, ProcessLock]()
  private val activeSessions = new ConcurrentHashMap[String, ActiveSession]()

  private def processLockFor(lockId: String): ProcessLock =
    processLocks.computeIfAbsent(lockId, _ => new ProcessLock(new Semaphore(1, true)))

  private def registerActiveSession(lockId: String, session: ActiveSession): Unit =
    activeSessions.put(lockId, session)

  private def shutdownActiveSession(lockId: String, owner: MillBuildServer): Boolean = {
    val session = activeSessions.get(lockId)
    if (session != null && session.owner.ne(owner)) {
      session.shutdown()
      true
    } else false
  }

  private def unregisterActiveSession(lockId: String, owner: MillBuildServer): Unit = {
    val session = activeSessions.get(lockId)
    if (session != null && session.owner.eq(owner))
      activeSessions.remove(lockId, session)
  }

  def enclosingRequestName(using enclosing: sourcecode.Enclosing): String = {
    var name0 = enclosing.value.split(" ") match {
      case Array(elem) => elem
      case other => other(other.length - 2)
    }

    val sharpIdx = name0.lastIndexOf('#')
    if (sharpIdx > 0)
      name0 = name0.drop(sharpIdx + 1)

    if (name0.startsWith("buildTarget")) {
      val stripped = name0.stripPrefix("buildTarget")
      if (stripped.headOption.exists(_.isUpper))
        name0 = stripped.head.toLower +: stripped.tail
    }
    name0
  }
}
