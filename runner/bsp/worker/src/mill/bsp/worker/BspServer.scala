package mill.bsp.worker

import ch.epfl.scala.bsp4j.*
import mill.api.*
import mill.bsp.worker.Utils.groupList
import mill.client.lock.Lock
import mill.api.internal.WatchSig
import mill.internal.PrefixLogger
import mill.server.Server
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import mill.api.daemon.internal.bsp.{BspModuleApi, BspServerResult}
import mill.api.daemon.internal.*

import scala.annotation.unused

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
    outLock: Lock,
    protected val baseLogger: Logger,
    out: os.Path
) extends EndpointsApi with AutoCloseable {

  import MillBuildServer.*

  // ==========================================================================
  // Session State
  // ==========================================================================

  // Mutable variables representing the lifecycle stages:
  protected var client: BuildClient = scala.compiletime.uninitialized
  protected var sessionInfo: MillBspEndpoints.SessionInfo = scala.compiletime.uninitialized
  private var bspEvaluators: Promise[BspEvaluators] = Promise[BspEvaluators]()
  private def bspEvaluatorsOpt(): Option[BspEvaluators] =
    bspEvaluators.future.value.flatMap(_.toOption)
  private var savedPreviousEvaluators = Option.empty[BspEvaluators]
  protected[worker] var sessionResult: Promise[BspServerResult] = Promise()

  private val requestCount = new AtomicInteger

  def initialized = sessionInfo != null

  // ==========================================================================
  // Lifecycle Management
  // ==========================================================================

  def close(): Unit = {
    stopped = true
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
  ): Unit = {
    baseLogger.debug(s"Updating Evaluator: $evaluators")

    val previousEvaluatorsOpt = bspEvaluatorsOpt().orElse(savedPreviousEvaluators)
    if (bspEvaluators.isCompleted) bspEvaluators = Promise[BspEvaluators]()

    val updatedEvaluators =
      if (errored) mergeWithPrevious(evaluators, previousEvaluatorsOpt.map(_.evaluators))
      else evaluators

    val evaluators0 = new BspEvaluators(
      topLevelProjectRoot,
      updatedEvaluators,
      s => baseLogger.debug(s()),
      watched
    )
    bspEvaluators.success(evaluators0)

    if (client != null && previousEvaluatorsOpt.nonEmpty) {
      val newTargetIds = evaluators0.bspModulesIdList.map { case (id, (_, ev)) => id -> ev }
      val previousTargetIds = previousEvaluatorsOpt.map(_.bspModulesIdList).getOrElse(Nil).map {
        case (id, (_, ev)) => id -> ev
      }
      ChangeNotifier.notifyChanges(client, previousTargetIds, newTargetIds)
    }
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
    savedPreviousEvaluators = bspEvaluatorsOpt().orElse(savedPreviousEvaluators)
    if (bspEvaluators.isCompleted) bspEvaluators = Promise[BspEvaluators]()
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
  )(block: TaskContext[W] => T)(
      agg: (java.util.List[T], BspEvaluators) => V
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
          try Seq(block(new TaskContext(id, m, values, ev, state)))
          catch {
            case NonFatal(e) =>
              logError(id, e.toString)
              Seq()
          }
        }
      }

      agg(evaluated.asJava, state)
    }
  }

  private val queue = new LinkedBlockingQueue[(BspEvaluators => Unit, Logger, String)]
  private var stopped = false

  /** Background thread that processes BSP requests sequentially. */
  private val evaluatorRequestsThread: Thread =
    mill.api.daemon.StartThread("mill-bsp-evaluator", daemon = true) {
      try {
        var pendingRequest = Option.empty[(BspEvaluators => Unit, Logger, String)]
        while (!stopped) {
          if (pendingRequest.isEmpty)
            pendingRequest = Option(queue.poll(1L, TimeUnit.SECONDS))

          for ((handler, logger, requestName) <- pendingRequest) {
            Await.result(bspEvaluators.future, Duration.Inf)
            Server.withOutLock(
              noBuildLock = false,
              noWaitForBuildLock = false,
              out = out,
              millActiveCommandMessage = s"IDE:$requestName",
              streams = logger.streams,
              outLock = outLock,
              setIdle = _ => ()
            ) {
              for (evaluator <- bspEvaluatorsOpt()) {
                if (evaluator.watched.forall(WatchSig.haveNotChanged)) {
                  pendingRequest = None
                  try handler(evaluator)
                  catch {
                    case t: Throwable =>
                      logger.error(s"Could not process request: $t")
                      t.printStackTrace(logger.streams.err)
                  }
                } else {
                  resetEvaluator()
                  sessionResult.trySuccess(BspServerResult.ReloadWorkspace)
                }
              }
            }
          }
        }
      } catch {
        case _: InterruptedException => // Normal exit
      }
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
        logger,
        prefix
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

    val result = Try(block)

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
      @unused requestDescription: String,
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
