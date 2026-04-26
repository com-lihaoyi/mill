package mill.bsp.worker

import ch.epfl.scala.bsp4j.*
import mill.api.*
import mill.bsp.worker.Utils.groupList
import mill.api.internal.WatchSig
import mill.internal.PrefixLogger
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.{CompletableFuture, Executors, ExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}
import mill.api.daemon.internal.NonFatal
import mill.api.daemon.Watchable
import mill.api.daemon.internal.bsp.{BspBootstrapBridge, BspModuleApi, BspServerResult}
import mill.api.daemon.internal.*

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

  protected[worker] val shutdownPromise: Promise[BspServerResult] = Promise[BspServerResult]()

  private val requestCount = new AtomicInteger

  private val bspRequestExecutor: ExecutorService = {
    val counter = new AtomicInteger(0)
    val threadCount = math.max(1, Runtime.getRuntime.availableProcessors())
    val threadFactory: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, s"mill-bsp-request-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
    Executors.newFixedThreadPool(threadCount, threadFactory)
  }

  def initialized = sessionInfo != null

  /**
   * Build a meta-build reporter for the BSP client. Each meta-build depth maps
   * to a synthetic BSP target whose URI is `<workspaceRoot>/mill-build/...`,
   * matching the IDs surfaced by [[BspEvaluators]] for `MillBuildRootModule`s.
   * Returns None when no client is connected (yet) or the depth is the user's
   * top-level build (depth 0 user code lives outside any meta-build target).
   */
  private def metaBuildReporterFor(depth: Int): Option[CompileProblemReporter] = {
    val currentClient = client
    if (currentClient == null) None
    else {
      val targetUri =
        Utils.sanitizeUri(topLevelProjectRoot.toNIO) +
          (Seq.fill(depth)("/mill-build")).mkString
      val targetId = new BuildTargetIdentifier(targetUri)
      val displayName = "mill-build" + (if (depth > 1) s" (level $depth)" else "")
      val taskId = new TaskId(s"mill-build-$depth")
      Some(new BspCompileProblemReporter(
        currentClient,
        targetId,
        displayName,
        taskId,
        // Match the user-build path (`Utils.getBspLoggedReporterPool("", …)`),
        // which threads an empty-string originId through `Option(originId)` to
        // `Some("")`. Using `None` here would omit the `originId` field from
        // emitted `PublishDiagnosticsParams`/`CompileReport` JSON, which the
        // BSP diagnostics snapshot tests assert is present (even when empty).
        compilationOriginId = Some("")
      ))
    }
  }

  private def withBootstrappedEvaluators[T](
      activeCommandMessage: String
  )(
      onUnavailable: (Seq[EvaluatorApi], Seq[Watchable], Option[String]) => T
  )(
      body: (BspEvaluators, Seq[EvaluatorApi], Seq[Watchable], Option[String]) => T
  ): T =
    bootstrapBridge.apply[T](
      activeCommandMessage,
      depth => metaBuildReporterFor(depth),
      (evaluators, watched, errorOpt) =>
        if (errorOpt.isDefined && evaluators.isEmpty)
          onUnavailable(evaluators, watched, errorOpt)
        else {
          val bspEvaluators = new BspEvaluators(
            topLevelProjectRoot,
            evaluators,
            s => baseLogger.debug(s()),
            watched
          )
          body(bspEvaluators, evaluators, watched, errorOpt)
        }
    )

  private val sessionCoordinator = new BspSessionCoordinator(
    out = out,
    sessionProcessPid = sessionProcessPid,
    noWaitForBspLock = noWaitForBspLock,
    killOther = killOther,
    logger = baseLogger,
    stopOwner = () => close()
  )

  protected def doneInitializingBuild(): Unit = {
    assert(initialized, "Expected Mill BSP server to be initialized")
    if (!sessionCoordinator.isInitialized) {
      val bspLockId = sessionInfo.clientDisplayName
        // just in case
        .replace(" ", "_")
        .replace("/", "_")
        .replace("\\", "_")
      sessionCoordinator.initialize(bspLockId)
      if (bspWatch) startWatcherThread()
    } else
      baseLogger.warn("Mill BSP server initialized more than once")
  }

  /**
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
            val watchedSeq =
              withBootstrappedEvaluators("BSP:watch")((_, watched, _) => watched) {
                (bspEvaluators, _, watched, _) =>
                  val current = bspEvaluators.targetSnapshots
                  val currentClient = client
                  if (seenAnyBootstrap && currentClient != null)
                    ChangeNotifier.notifyChanges(
                      currentClient,
                      prevTargetSnapshots,
                      current
                    )
                  prevTargetSnapshots = current
                  seenAnyBootstrap = true
                  watched
              }

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

  def close(): Unit = {
    stopped = true
    try sessionCoordinator.close()
    catch { case _: Throwable => () }
    shutdownPromise.trySuccess(BspServerResult.Shutdown)
    if (watcherThread != null) watcherThread.interrupt()
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
    if (future.isCancelled()) {
      logger.info(s"$prefix was cancelled")
      return
    }
    try {
      withBootstrappedEvaluators(s"BSP:$prefix") { (_, _, errorOpt) =>
        val error = errorOpt.get
        logger.error(error)
        future.completeExceptionally(new IllegalStateException(error))
      } { (bspEvaluators, _, _, _) =>
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
