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
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}
import mill.api.daemon.internal.NonFatal
import mill.api.daemon.internal.bsp.{BspModuleApi, BspServerResult}
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
    killOther: Boolean
) extends EndpointsApi with AutoCloseable {

  import MillBuildServer.*

  // ==========================================================================
  // Session State
  // ==========================================================================

  // Mutable variables representing the lifecycle stages:
  @volatile protected var client: BuildClient = scala.compiletime.uninitialized
  @volatile protected var sessionInfo: MillBspEndpoints.SessionInfo = scala.compiletime.uninitialized
  private val snapshotLock = new Object
  private case class Snapshot(
      evaluators: BspEvaluators,
      sessionResult: Promise[BspServerResult]
  )
  private case class SnapshotState(
      current: Option[Snapshot] = None,
      savedPreviousEvaluators: Option[BspEvaluators] = None,
      nextSnapshot: Promise[Snapshot] = Promise[Snapshot]()
  )
  private var snapshotState = SnapshotState()

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
    } else
      baseLogger.warn("Mill BSP server initialized more than once")
  }

  // ==========================================================================
  // Lifecycle Management
  // ==========================================================================

  def close(): Unit = {
    stopped = true
    // Wake any thread parked in `awaitFreshSnapshot` so it can observe
    // `stopped` and exit instead of blocking on a snapshot that will never
    // be published.
    snapshotLock.synchronized {
      snapshotState.nextSnapshot.tryFailure(
        new java.util.concurrent.CancellationException("BSP server shutting down")
      )
      snapshotState.current.foreach(
        _.sessionResult.tryFailure(
          new java.util.concurrent.CancellationException("BSP server shutting down")
        )
      )
    }
    evaluatorRequestsThread.interrupt()
  }

  /**
   * Updates the BSP server evaluators.
   *
   * If errored is true, this method attempts to keep former evaluators for
   * build levels that don't have new evaluators, so IDE features still work
   * for non-broken parts of the build.
   */
  def updateEvaluator(
      evaluators: Seq[EvaluatorApi],
      errored: Boolean,
      watched: Seq[Watchable]
  ): Future[BspServerResult] = {
    baseLogger.debug(s"Updating Evaluator: $evaluators")

    val (previousEvaluatorsOpt, evaluators0, sessionFuture) = snapshotLock.synchronized {
      val previousEvaluatorsOpt =
        snapshotState.current.map(_.evaluators).orElse(snapshotState.savedPreviousEvaluators)

      val updatedEvaluators =
        if (errored) mergeWithPrevious(evaluators, previousEvaluatorsOpt.map(_.evaluators))
        else evaluators

      val evaluators0 = new BspEvaluators(
        topLevelProjectRoot,
        updatedEvaluators,
        s => baseLogger.debug(s()),
        watched
      )
      val snapshot = Snapshot(
        evaluators = evaluators0,
        sessionResult = Promise[BspServerResult]()
      )
      val pendingSnapshot = snapshotState.nextSnapshot
      snapshotState = snapshotState.copy(
        current = Some(snapshot),
        savedPreviousEvaluators = None,
        nextSnapshot = Promise[Snapshot]()
      )
      pendingSnapshot.trySuccess(snapshot)
      (previousEvaluatorsOpt, evaluators0, snapshot.sessionResult.future)
    }

    if (client != null && previousEvaluatorsOpt.nonEmpty) {
      ChangeNotifier.notifyChanges(
        client,
        previousEvaluatorsOpt.map(_.targetSnapshots).getOrElse(Nil),
        evaluators0.targetSnapshots,
        forceMillBuildChanged = errored
      )
    }
    sessionFuture
  }

  /** Merges new evaluators with previous ones when the build is errored. */
  private def mergeWithPrevious(
      newEvaluators: Seq[EvaluatorApi],
      previousOpt: Option[Seq[EvaluatorApi]]
  ): Seq[EvaluatorApi] = previousOpt match {
    case None => newEvaluators
    case Some(previous) =>
      newEvaluators.headOption match {
        case None => previous
        case Some(head) =>
          val idx = previous.indexWhere(_.outPathJava == head.outPathJava)
          if (idx < 0) newEvaluators
          else previous.take(idx) ++ newEvaluators
      }
  }

  def resetEvaluator(): Unit = {
    baseLogger.debug("Resetting Evaluator")
    snapshotLock.synchronized {
      snapshotState = snapshotState.copy(
        savedPreviousEvaluators =
          snapshotState.current.map(_.evaluators).orElse(snapshotState.savedPreviousEvaluators),
        current = None
      )
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

  private val queue = new LinkedBlockingQueue[(BspEvaluators => Unit, Logger)]
  private var stopped = false

  /** Background thread that processes BSP requests sequentially. */
  private val evaluatorRequestsThread: Thread =
    mill.api.daemon.StartThread("mill-bsp-evaluator", daemon = true) {
      try {
        var pendingRequest = Option.empty[(BspEvaluators => Unit, Logger)]
        while (!stopped) {
          if (pendingRequest.isEmpty)
            pendingRequest = Option(queue.poll(1L, TimeUnit.SECONDS))

          for ((handler, logger) <- pendingRequest) {
            val snapshot = awaitFreshSnapshot(logger)
            pendingRequest = None
            try handler(snapshot.evaluators)
            catch {
              case t: Throwable =>
                logger.error(s"Could not process request: $t")
                t.printStackTrace(logger.streams.err)
            }
          }
        }
      } catch {
        case _: InterruptedException => // Normal exit
      }
    }

  /**
   * Returns a current snapshot whose watched inputs are still valid, blocking
   * on the next bootstrap publication if the current one is stale or absent.
   * Throws [[InterruptedException]] when [[close]] cancels the wait so the
   * background request thread can exit cleanly.
   */
  private def awaitFreshSnapshot(logger: Logger): Snapshot = {
    def loop(): Snapshot = {
      val currentOrNext = snapshotLock.synchronized {
        snapshotState.current match {
          case Some(snapshot) if snapshot.evaluators.watched.forall(WatchSig.haveNotChanged) =>
            Left(snapshot)
          case Some(snapshot) =>
            // Stale: evict and signal the launcher loop to reload via the
            // session result. The next bootstrap publication completes
            // `nextSnapshot`. Holding `snapshotLock` from the outer match
            // through this branch makes the eviction atomic — no inner
            // generation re-check needed.
            snapshotState = snapshotState.copy(
              savedPreviousEvaluators = Some(snapshot.evaluators),
              current = None
            )
            snapshot.sessionResult.trySuccess(BspServerResult.ReloadWorkspace)
            Right(snapshotState.nextSnapshot.future)
          case None =>
            Right(snapshotState.nextSnapshot.future)
        }
      }

      currentOrNext match {
        case Left(snapshot) => snapshot
        case Right(nextSnapshotFuture) =>
          logger.debug("Waiting for a fresh BSP bootstrap snapshot")
          // Poll with a finite timeout so `close()` can wake us promptly via
          // `stopped`. `Await.result(_, Duration.Inf)` would park here past
          // shutdown.
          while (!nextSnapshotFuture.isCompleted) {
            if (stopped) throw new InterruptedException("BSP server shutting down")
            try Await.ready(nextSnapshotFuture, Duration(250, TimeUnit.MILLISECONDS))
            catch { case _: java.util.concurrent.TimeoutException => () }
          }
          if (stopped) throw new InterruptedException("BSP server shutting down")
          loop()
      }
    }

    loop()
  }

  protected def completeSessionResult(result: BspServerResult): Unit =
    snapshotLock.synchronized {
      snapshotState.current.foreach(_.sessionResult.trySuccess(result))
    }

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
    } else {
      queue.put((
        evaluators => {
          if (!future.isCancelled()) {
            executeWithTiming(prefix, logger, future)(block(evaluators, logger))
          } else {
            logger.info(s"$prefix was cancelled")
          }
        },
        logger
      ))
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
