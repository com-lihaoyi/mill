package mill.bsp.worker

import ch.epfl.scala.bsp4j.*
import mill.api.*
import mill.bsp.worker.Utils.groupList
import mill.client.lock.Lock
import mill.api.internal.WatchSig
import mill.internal.PrefixLogger
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}
import mill.api.daemon.internal.NonFatal
import mill.api.daemon.internal.bsp.{
  BspBootstrapBridge,
  BspModuleApi,
  BspServerResult
}
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
  @volatile protected var sessionInfo: MillBspEndpoints.SessionInfo = scala.compiletime.uninitialized

  /**
   * Completes when the BSP client signals end-of-session (`build/exit`) or
   * requests `workspace/reload`. The daemon awaits this future to know when
   * to exit its BSP block.
   */
  protected[worker] val shutdownPromise: Promise[BspServerResult] = Promise[BspServerResult]()

  private val requestCount = new AtomicInteger

  def initialized = sessionInfo != null

  private var bspLock: Lock = scala.compiletime.uninitialized

  private def initLock(bspLockId: String): Unit = {
    assert(bspLock == null)
    bspLock = Lock.file((out / OutFiles.millBspLock(bspLockId)).toString)
    val activeBspFile = out / OutFiles.millActiveBsp(bspLockId)
    def readActiveInfo(): Option[Long] =
      try {
        val json = os.read(activeBspFile)
        val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
        pidPattern.findFirstMatchIn(json).flatMap(m => m.group(1).toLongOption)
      } catch {
        case NonFatal(_) => None
      }

    val tryLocked = bspLock.tryLock()
    if (tryLocked.isLocked)
      tryLocked
    else if (noWaitForBspLock)
      throw new Exception("Another Mill BSP process is running, failing")
    else {
      val pidOpt = readActiveInfo()
      if (killOther)
        pidOpt match {
          case Some(pid) =>
            val handle = ProcessHandle.of(pid).orElseThrow()
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
          case None =>
            baseLogger.warn(
              s"PID of other Mill process not found in $activeBspFile, could not terminate it"
            )
        }
      else
        baseLogger.info(
          s"Another Mill BSP server is running with PID ${pidOpt.fold("<unknown>")(_.toString)} waiting for it to be done..."
        )
      bspLock.lock()
    }

    val pid = ProcessHandle.current().pid()
    val json = s"""{"pid":$pid}"""
    os.write.over(activeBspFile, json)
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
            // Holding leases during the poll would block any concurrent BSP
            // request that needs to acquire a meta-build/task write lease
            // (e.g. when build.mill changed and the request needs to refresh
            // the meta-build classloader), creating a deadlock — the request
            // can't make progress until the watcher releases, and the watcher
            // only loops when something changes, which a blocked request can't
            // produce.
            val watchedSeq = bootstrapBridge.runBootstrap(
              "BSP:watch",
              new BspBootstrapBridge.Body[Seq[Watchable]] {
                override def apply(state: BspBootstrapBridge.BootstrapState): Seq[Watchable] =
                  if (state.errorOpt.isDefined && state.evaluators.isEmpty) {
                    state.watched.asScala.toSeq
                  } else {
                    val bspEvaluators = new BspEvaluators(
                      topLevelProjectRoot,
                      state.evaluators.asScala.toSeq,
                      s => baseLogger.debug(s()),
                      state.watched.asScala.toSeq,
                      state.errorOpt
                    )
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
              }
            )

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
    evaluatorRequestsThread.interrupt()
    if (watcherThread != null) watcherThread.interrupt()
    // Pending queued requests are drained by the evaluator thread itself
    // when it exits its run loop (see [[evaluatorRequestsThread]]), so any
    // request that the evaluator thread had already started processing
    // gets a chance to complete normally before its future is failed.
  }

  private def drainPendingRequests(cause: Throwable): Unit = {
    val drained = new java.util.ArrayList[QueuedRequest]
    queue.drainTo(drained)
    drained.forEach(req =>
      try req.failFuture(cause)
      catch { case _: Throwable => }
    )
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

  /**
   * One queued request: a pre-prepared logger, a request name (for active-
   * command messages and timing), the client-facing future, the body closure
   * that runs against fresh evaluators+watches obtained per-request from the
   * bootstrap bridge, and a callback to fail the future if the request can't
   * be processed (bootstrap throw, server shutdown, etc.).
   */
  private case class QueuedRequest(
      requestName: String,
      logger: Logger,
      future: CompletableFuture[?],
      run: BspBootstrapBridge.BootstrapState => Unit,
      failFuture: Throwable => Unit
  )

  private val queue = new LinkedBlockingQueue[QueuedRequest]
  private var stopped = false

  /**
   * Background thread that processes BSP evaluator requests sequentially.
   *
   * Each request bootstraps fresh evaluators by calling
   * [[BspBootstrapBridge.runBootstrap]], runs the body, and lets the daemon
   * tear the bootstrap state down before the next request runs. Sequential
   * processing is preserved (one bootstrap at a time on this thread) so that
   * cross-request ordering matches the previous snapshot-cache design;
   * concurrent CLI launchers are unaffected because the per-request
   * bootstrap takes its own meta-build read leases via
   * [[mill.daemon.MillBuildBootstrap.processRunClasspath]]'s read-first
   * speculation, which composes safely with concurrent task and meta-build
   * read leases held by other launchers.
   */
  private val evaluatorRequestsThread: Thread =
    mill.api.daemon.StartThread("mill-bsp-evaluator", daemon = true) {
      try {
        while (!stopped) {
          val req = queue.poll(1L, TimeUnit.SECONDS)
          if (req != null) runQueuedRequest(req)
        }
      } catch {
        case _: InterruptedException => // Normal exit
      } finally {
        // Drain any requests still queued at shutdown time. Their bodies
        // never ran, so without this their CompletableFutures (returned
        // earlier to lsp4j) would never complete and the client would
        // hang. Done here, after the run loop exits, so we don't race
        // with in-flight requests that close() interrupted.
        drainPendingRequests(new java.util.concurrent.CancellationException(
          "BSP server is shutting down"
        ))
      }
    }

  private def runQueuedRequest(req: QueuedRequest): Unit = {
    // Fast path: if the future was cancelled before this request reached
    // the worker thread (lsp4j's `$/cancelRequest` got delivered before we
    // dequeued), skip the bootstrap entirely and just log "was cancelled".
    // Skipping bootstrap also avoids racing with a near-simultaneous
    // shutdown that could interrupt the bootstrap before req.run runs.
    if (req.future.isCancelled()) {
      req.logger.info(s"${req.requestName} was cancelled")
      return
    }
    try {
      bootstrapBridge.runBootstrap(
        s"BSP:${req.requestName}",
        new BspBootstrapBridge.Body[Unit] {
          override def apply(state: BspBootstrapBridge.BootstrapState): Unit =
            if (state.errorOpt.isDefined && state.evaluators.isEmpty) {
              val error = state.errorOpt.get
              req.logger.error(error)
              req.failFuture(new IllegalStateException(error))
            } else req.run(state)
        }
      )
    } catch {
      case t: Throwable =>
        req.logger.error(s"Could not process request: $t")
        t.printStackTrace(req.logger.streams.err)
        // If the bootstrap threw before req.run could complete the future,
        // surface the failure to the client; otherwise this is a no-op.
        req.failFuture(t)
    }
  }

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
      val req = QueuedRequest(
        requestName = prefix,
        logger = logger,
        future = future,
        run = bootstrapState => {
          // The cancel check is also done in `runQueuedRequest` before the
          // bootstrap, but a request might be cancelled between bootstrap
          // start and body invocation; preserve that behavior here too.
          if (future.isCancelled()) {
            logger.info(s"$prefix was cancelled")
          } else {
            val bspEvaluators = new BspEvaluators(
              topLevelProjectRoot,
              bootstrapState.evaluators.asScala.toSeq,
              s => baseLogger.debug(s()),
              bootstrapState.watched.asScala.toSeq,
              bootstrapState.errorOpt
            )
            executeWithTiming(prefix, logger, future)(block(bspEvaluators, logger))
          }
        },
        failFuture = t => future.completeExceptionally(t)
      )
      queue.put(req)
      // If close() ran between our `stopped` check and the `put`, the
      // evaluator thread's `finally`-block drain will pick up this entry
      // and fail its future on its way out. No need to drain here.
    }
    future
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
