package mill.exec

import mill.api.daemon.internal.*
import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.api.*
import mill.internal.{CodeSigUtils, JsonArrayLogger, PrefixLogger, SpanningForest}

import java.util.concurrent.{ConcurrentHashMap, ThreadPoolExecutor}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.concurrent.*

/**
 * Core logic of evaluating tasks, without any user-facing helper methods
 */
case class Execution(
    baseLogger: Logger,
    profileLogger: JsonArrayLogger.Profile,
    workspace: os.Path,
    outPath: os.Path,
    externalOutPath: os.Path,
    rootModule: BaseModuleApi,
    classLoaderSigHash: Int,
    classLoaderIdentityHash: Int,
    workerCache: mutable.Map[String, (Int, Val, TaskApi[?])],
    env: Map[String, String],
    failFast: Boolean,
    ec: Option[ThreadPoolExecutor],
    codeSignatures: Map[String, Int],
    systemExit: ( /* reason */ String, /* exitCode */ Int) => Nothing,
    exclusiveSystemStreams: SystemStreams,
    getEvaluator: () => EvaluatorApi,
    offline: Boolean,
    useFileLocks: Boolean,
    workspaceLocking: LauncherLocking,
    runArtifacts: LauncherOutFiles,
    staticBuildOverrideFiles: Map[java.nio.file.Path, String],
    enableTicker: Boolean,
    depth: Int,
    isFinalDepth: Boolean,
    // JSON string to avoid classloader issues when crossing classloader boundaries
    spanningInvalidationTree: Option[String],
    // Tracks tasks invalidated due to version/classloader mismatch
    versionMismatchReasons: ConcurrentHashMap[Task[?], String] = new ConcurrentHashMap()
) extends GroupExecution with AutoCloseable {

  // Track nesting depth of executeTasks calls to only show final status on outermost call
  private val executionNestingDepth = new AtomicInteger(0)

  // Lazily computed worker dependency graph, cached for the duration of the execution. It's
  // ok to take a snapshot of the cache, since the workerCache entries we may want to remove
  // must be from previous evaluation runs and wouldn't be added as part of this evaluation
  lazy val (workerDeps, reverseDeps, workerTopoIndex) = {
    val cacheSnapshot = workerCache.synchronized { workerCache.toMap }
    val deps = GroupExecution.workerDependencies(cacheSnapshot)
    val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
    (deps, SpanningForest.reverseEdges(deps), topoIndex)
  }

  // this (shorter) constructor is used from [[mill.daemon.MillBuildBootstrap]] via reflection
  def this(
      baseLogger: Logger,
      workspace: java.nio.file.Path,
      outPath: java.nio.file.Path,
      externalOutPath: java.nio.file.Path,
      rootModule: BaseModuleApi,
      classLoaderSigHash: Int,
      classLoaderIdentityHash: Int,
      workerCache: mutable.Map[String, (Int, Val, TaskApi[?])],
      env: Map[String, String],
      failFast: Boolean,
      ec: Option[ThreadPoolExecutor],
      codeSignatures: Map[String, Int],
      systemExit: ( /* reason */ String, /* exitCode */ Int) => Nothing,
      exclusiveSystemStreams: SystemStreams,
      getEvaluator: () => EvaluatorApi,
      offline: Boolean,
      useFileLocks: Boolean,
      workspaceLocking: LauncherLocking,
      runArtifacts: LauncherOutFiles,
      staticBuildOverrideFiles: Map[java.nio.file.Path, String],
      enableTicker: Boolean,
      depth: Int,
      isFinalDepth: Boolean,
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String]
  ) = this(
    baseLogger = baseLogger,
    profileLogger = new JsonArrayLogger.Profile(runArtifacts match {
      case LauncherOutFiles.Noop => os.Path(outPath) / "mill-profile.json"
      case _ => os.Path(runArtifacts.profile)
    }),
    workspace = os.Path(workspace),
    outPath = os.Path(outPath),
    externalOutPath = os.Path(externalOutPath),
    rootModule = rootModule,
    classLoaderSigHash = classLoaderSigHash,
    classLoaderIdentityHash = classLoaderIdentityHash,
    workerCache = workerCache,
    env = EnvMap.asEnvMap(env),
    failFast = failFast,
    ec = ec,
    codeSignatures = codeSignatures,
    systemExit = systemExit,
    exclusiveSystemStreams = exclusiveSystemStreams,
    getEvaluator = getEvaluator,
    offline = offline,
    useFileLocks = useFileLocks,
    workspaceLocking = workspaceLocking,
    runArtifacts = runArtifacts,
    staticBuildOverrideFiles = staticBuildOverrideFiles,
    enableTicker = enableTicker,
    depth = depth,
    isFinalDepth = isFinalDepth,
    spanningInvalidationTree = spanningInvalidationTree
  )

  def withBaseLogger(newBaseLogger: Logger) = {
    // If the new logger redirects stdout to stderr (e.g. when `show` is used),
    // also redirect exclusiveSystemStreams so exclusive tasks like `resolve` and
    // `plan` also send their output to stderr rather than stdout.
    val newExclusiveSystemStreams =
      if (newBaseLogger.redirectOutToErr) {
        val err = exclusiveSystemStreams.err
        new SystemStreams(err, err, exclusiveSystemStreams.in)
      } else exclusiveSystemStreams
    this.copy(baseLogger = newBaseLogger, exclusiveSystemStreams = newExclusiveSystemStreams)
  }

  def withIsFinalDepth(newIsFinalDepth: Boolean) = this.copy(isFinalDepth = newIsFinalDepth)

  // The trait method retainTerminalReadLock is no longer used; lease
  // retention is plumbed per-execute0 via the LeaseTracker passed into
  // executeGroupCached. The trait implementation routes to a no-op so
  // that anyone still calling it (defensively) doesn't crash, but the
  // expected pathway is via the tracker.
  override def retainTerminalReadLock(
      task: Task[?],
      lease: LauncherLocking.Lease
  ): Unit = {
    // Should never be called; per-execute0 leaseTracker.retain is the
    // intended path. Close the lease defensively to avoid leaks.
    try lease.close()
    catch { case _: Throwable => () }
  }

  /**
   * Build a fresh per-execute0 lease tracker. Each `execute0` call creates
   * its own tracker so nested calls (e.g. a Task.Command using
   * `getEvaluator().executeApi(...)` to evaluate a sub-task graph) cannot
   * stomp on each other's per-task queues. Each tracker owns:
   *
   *   - retainedLeasesByTask: queue of leases retained for each terminal,
   *     released as soon as every in-run downstream consumer has finished
   *   - pendingDownstreamCount: per-terminal countdown; release the
   *     terminal's leases when its count hits zero
   *   - orphanedLeases: leases retained for tasks not in this run's graph
   *     (e.g. build-override leases via [[evaluateBuildOverrideOnly]]),
   *     drained when the tracker is closed
   *
   * `onCompleted(terminal)` is called when each terminal's group finishes;
   * it decrements the pending-downstream count for `terminal`'s deps and
   * releases their retained leases when the count reaches zero. It also
   * releases `terminal`'s own leases if `terminal` has no in-run downstream
   * (count was already zero at construction).
   */
  private def newDownstreamTracker(
      indexToTerminal: Array[Task[?]],
      interGroupDeps: Map[Task[?], Seq[Task[?]]]
  ): Execution.LeaseTracker = {
    val retainedLeasesByTask =
      new ConcurrentHashMap[Task[?], java.util.concurrent.ConcurrentLinkedQueue[
        LauncherLocking.Lease
      ]]()
    val pendingCount = new ConcurrentHashMap[Task[?], AtomicInteger]()
    for (t <- indexToTerminal) {
      pendingCount.put(t, new AtomicInteger(0))
      retainedLeasesByTask.put(
        t,
        new java.util.concurrent.ConcurrentLinkedQueue[LauncherLocking.Lease]()
      )
    }
    for ((_, deps) <- interGroupDeps; dep <- deps) {
      val c = pendingCount.get(dep)
      if (c != null) c.incrementAndGet()
    }

    val orphanedLeases =
      new java.util.concurrent.ConcurrentLinkedQueue[LauncherLocking.Lease]()

    def releaseLeasesFor(task: Task[?]): Unit = {
      val q = retainedLeasesByTask.remove(task)
      if (q != null) {
        var lease = q.poll()
        while (lease != null) {
          try lease.close()
          catch { case _: Throwable => () }
          lease = q.poll()
        }
      }
    }

    new Execution.LeaseTracker {
      override def retain(task: Task[?], lease: LauncherLocking.Lease): Unit = {
        val q = retainedLeasesByTask.get(task)
        if (q != null) q.add(lease)
        else orphanedLeases.add(lease)
      }

      override def onCompleted(terminal: Task[?]): Unit = {
        // Decrement counts for our deps; release theirs if hit zero.
        for (dep <- interGroupDeps.getOrElse(terminal, Nil)) {
          val c = pendingCount.get(dep)
          if (c != null && c.decrementAndGet() == 0) releaseLeasesFor(dep)
        }
        // If our own count is already zero (i.e. nothing in this run depends
        // on us), release our own leases now too.
        val ownCount = pendingCount.get(terminal)
        if (ownCount != null && ownCount.get() == 0) releaseLeasesFor(terminal)
      }

      override def drain(): Unit = {
        import scala.jdk.CollectionConverters.*
        val leftover = retainedLeasesByTask.values().asScala
          .flatMap(q => Iterator.continually(q.poll()).takeWhile(_ != null))
        val orphans = Iterator.continually(orphanedLeases.poll()).takeWhile(_ != null)
        (leftover.iterator ++ orphans).foreach { lease =>
          try lease.close()
          catch { case _: Throwable => () }
        }
        retainedLeasesByTask.clear()
      }
    }
  }

  /**
   * @param goals The tasks that need to be evaluated
   * @param reporter A function that will accept a module id and provide a listener for build problems in that module
   * @param testReporter Listener for test events like start, finish with success/error
   */
  def executeTasks(
      goals: Seq[Task[?]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      logger: Logger = baseLogger,
      serialCommandExec: Boolean = false
  ): Execution.Results = logger.prompt.withPromptUnpaused {
    os.makeDir.all(outPath)
    executionNestingDepth.incrementAndGet()
    try {
      PathRef.validatedPaths.withValue(new PathRef.ValidatedPaths()) {
        execute0(goals, logger, reporter, testReporter, serialCommandExec)
      }
    } finally executionNestingDepth.decrementAndGet()
  }

  private def execute0(
      goals: Seq[Task[?]],
      logger: Logger,
      reporter: Int => Option[
        CompileProblemReporter
      ] /* = _ => Option.empty[CompileProblemReporter]*/,
      testReporter: TestReporter /* = TestReporter.DummyTestReporter*/,
      serialCommandExec: Boolean
  ): Execution.Results = {
    os.makeDir.all(outPath)
    val failed = new AtomicBoolean(false)
    val count = new AtomicInteger(1)
    val completedCount = new AtomicInteger(0)
    val rootFailedCount = new AtomicInteger(0) // Track only root failures
    val planningLogger = new PrefixLogger(
      logger0 = baseLogger,
      key0 = Seq("planning"),
      message = "planning"
    )
    val (
      plan,
      interGroupDeps,
      indexToTerminal,
      classToTransitiveClasses,
      allTransitiveClassMethods
    ) = planningLogger.withPromptLine {
      val plan = PlanImpl.plan(goals)
      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)
      val indexToTerminal = plan.sortedGroups.keys().toArray
      ExecutionLogs.logDependencyTree(
        interGroupDeps,
        indexToTerminal,
        outPath,
        runArtifacts
      )
      // Prepare a lookup tables up front of all the method names that each class owns,
      // and the class hierarchy, so during evaluation it is cheap to look up what class
      // each task belongs to determine of the enclosing class code signature changed.
      val (classToTransitiveClasses, allTransitiveClassMethods) =
        CodeSigUtils.precomputeMethodNamesPerClass(PlanImpl.transitiveNamed(goals))
      (plan, interGroupDeps, indexToTerminal, classToTransitiveClasses, allTransitiveClassMethods)
    }
    baseLogger.withChromeProfile("execution") {
      val uncached = new ConcurrentHashMap[Task[?], Unit]()
      val changedValueHash = new ConcurrentHashMap[Task[?], Unit]()
      val prefixes = new ConcurrentHashMap[Task[?], Seq[String]]()
      // The tracker is constructed below (`tracker`) and drained at the end
      // of this block, so any leases retained by terminals whose downstream
      // count never reached zero (e.g. due to early failure) and any
      // orphaned leases (e.g. evaluateBuildOverrideOnly's) are released
      // before this execute0 returns.

      val futures = mutable.Map.empty[Task[?], Future[Option[GroupExecution.Results]]]

      val keySuffix = s"/${indexToTerminal.size}"
      // Add a extra marker if we're on a deeper level, https://github.com/com-lihaoyi/mill/issues/6817
      val extraKeySuffix = if (isFinalDepth) "" else "+"

      def formatHeaderPrefix(completed: Boolean = false) = {
        val completedMsg = mill.api.internal.Util.leftPad(
          completedCount.get().toString,
          indexToTerminal.size.toString.length,
          '0'
        )
        s"$completedMsg$keySuffix$extraKeySuffix${Execution.formatFailedCount(rootFailedCount.get(), completed, logger.prompt.errorColor, logger.prompt.successColor)}"
      }

      val tasksTransitive = PlanImpl.transitiveTasks(Seq.from(indexToTerminal)).toSet
      val downstreamEdges: Map[Task[?], Set[Task[?]]] =
        tasksTransitive.flatMap(t => t.inputs.map(_ -> t)).groupMap(_._1)(_._2)

      val allExclusiveCommands = tasksTransitive.filter(_.isExclusiveCommand)
      val downstreamOfExclusive =
        mill.internal.SpanningForest.breadthFirst[Task[?]](allExclusiveCommands)(t =>
          downstreamEdges.getOrElse(t, Set())
        )

      // Per-execute0 lease tracker: each call (including nested calls via
      // getEvaluator().executeApi) gets its own, so overlapping task IDs
      // across nested executions never collide on a shared per-task queue.
      // The tracker is captured by lexical scope in the future bodies and
      // passed explicitly into executeGroupCached so GroupExecution's lease
      // retention routes back to THIS execute0's tracker.
      val tracker = newDownstreamTracker(indexToTerminal, interGroupDeps)
      def onTerminalCompleted(t: Task[?]): Unit = tracker.onCompleted(t)
      try {

      def evaluateTerminals(
          terminals: Seq[Task[?]],
          exclusive: Boolean
      ) = {
        val forkExecutionContext =
          ec.fold(ExecutionContexts.RunNow)(new ExecutionContexts.ThreadPool(_))
        implicit val taskExecutionContext =
          if (exclusive) ExecutionContexts.RunNow else forkExecutionContext
        // We walk the task graph in topological order and schedule the futures
        // to run asynchronously. During this walk, we store the scheduled futures
        // in a dictionary. When scheduling each future, we are guaranteed that the
        // necessary upstream futures will have already been scheduled and stored,
        // due to the topological order of traversal.
        for (terminal <- terminals) {
          val deps = interGroupDeps(terminal)

          val group = plan.sortedGroups.lookupKey(terminal)
          val exclusiveDeps = deps.filter(d => d.isExclusiveCommand)

          if (terminal.asCommand.isEmpty && downstreamOfExclusive.contains(terminal)) {
            val failure = ExecResult.Failure(
              s"Non-Command task ${terminal} cannot depend on exclusive command " +
                exclusiveDeps.mkString(", ")
            )
            val taskResults: Map[Task[?], ExecResult.Failing[Nothing]] = group
              .map(t => (t, failure))
              .toMap

            // Synthetic failure path: no group actually ran, so nothing was
            // retained for this terminal. Still notify the tracker so any
            // upstream we "consumed" (none here, but symmetric with the real
            // path) gets its downstream count decremented.
            onTerminalCompleted(terminal)
            futures(terminal) = Future.successful(
              Some(GroupExecution.Results(
                newResults = taskResults,
                newEvaluated = group.toSeq,
                cached = false,
                inputsHash = -1,
                previousInputsHash = -1,
                valueHashChanged = false,
                serializedPaths = Nil
              ))
            )
          } else {
            futures(terminal) = Future.sequence(deps.map(futures)).map { upstreamValues =>
              try {
                val countMsg = mill.api.internal.Util.leftPad(
                  count.getAndIncrement().toString,
                  terminals.length.toString.length,
                  '0'
                )

                val contextLogger = new PrefixLogger(
                  logger0 = logger,
                  key0 = Seq(countMsg),
                  keySuffix = keySuffix,
                  message = terminal.toString,
                  noPrefix = exclusive
                )

                if (enableTicker) prefixes.put(terminal, contextLogger.logKey)
                contextLogger.withPromptLine {
                  logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix())

                  if (failed.get()) None
                  else {
                    val upstreamResults = upstreamValues
                      .iterator
                      .flatMap(_.iterator.flatMap(_.newResults))
                      .toMap

                    val upstreamPathRefs = upstreamValues
                      .iterator
                      .flatMap(_.iterator.flatMap(_.serializedPaths))
                      .toSeq

                    val startTime = System.nanoTime() / 1000

                    val res = executeGroupCached(
                      terminal = terminal,
                      group = plan.sortedGroups.lookupKey(terminal).toSeq,
                      results = upstreamResults,
                      countMsg = countMsg,
                      zincProblemReporter = reporter,
                      testReporter = testReporter,
                      logger = contextLogger,
                      deps = deps,
                      classToTransitiveClasses = classToTransitiveClasses,
                      allTransitiveClassMethods = allTransitiveClassMethods,
                      executionContext = forkExecutionContext,
                      exclusive = exclusive,
                      upstreamPathRefs = upstreamPathRefs,
                      leaseTracker = tracker
                    )

                    // Count new failures - if there are upstream failures, tasks should be skipped, not failed
                    val newFailures = res.newResults.values.count(r => r.asFailing.isDefined)

                    rootFailedCount.addAndGet(newFailures)
                    completedCount.incrementAndGet()

                    // Always show completed count in header after task finishes
                    logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix())

                    if (failFast && res.newResults.values.exists(_.asSuccess.isEmpty))
                      failed.set(true)

                    val endTime = System.nanoTime() / 1000
                    val duration = endTime - startTime

                    if (!res.cached) uncached.put(terminal, ())
                    if (res.valueHashChanged) changedValueHash.put(terminal, ())

                    profileLogger.log(
                      terminal.toString,
                      duration,
                      res.cached,
                      res.valueHashChanged,
                      deps.map(_.toString),
                      res.inputsHash,
                      res.previousInputsHash
                    )

                    Some(res)
                  }
                }
              } catch {
                // Let StopWithResponse propagate - it's a controlled shutdown signal
                case e: mill.api.daemon.StopWithResponse[?] => throw e
                // Wrapping the fatal error in a non-fatal exception, so it would be caught by Scala's Future
                // infrastructure, rather than silently terminating the future and leaving downstream Awaits hanging.
                case e: Throwable if !mill.api.daemon.internal.NonFatal(e) =>
                  val nonFatal = new Exception(s"fatal exception occurred: $e", e)
                  // Set the stack trace of the non-fatal exception to the original exception's stack trace
                  // as it actually indicates the location of the error.
                  nonFatal.setStackTrace(e.getStackTrace)
                  throw nonFatal
              } finally {
                // Whether the group ran successfully, was skipped due to an
                // upstream failure, or threw, this terminal is now "done":
                // notify the downstream tracker so any in-run consumers we
                // were upstream of can decrement our retained-leases count
                // (and so OUR leases can be released early if nothing in this
                // run is downstream of us).
                onTerminalCompleted(terminal)
              }
            }
          }
        }

        // Make sure we wait for all tasks from this batch to finish before starting the next
        // one, so we don't mix up exclusive and non-exclusive tasks running at the same time
        terminals.map(t => (t, Await.result(futures(t), duration.Duration.Inf)))
      }

      val (nonExclusiveTasks, leafExclusiveCommands) = indexToTerminal.partition {
        case t: Task.Named[_] => !downstreamOfExclusive.contains(t)
        case _ => !serialCommandExec
      }

      // Run all non-command tasks according to the threads
      // given but run the commands in linear order
      val nonExclusiveResults = evaluateTerminals(nonExclusiveTasks, exclusive = false)

      val exclusiveResults = evaluateTerminals(leafExclusiveCommands, exclusive = true)

      // Set final header showing SUCCESS/FAILED status:
      // - FAILED: show for any outermost execution with failures (meta-build failures terminate bootstrapping)
      // - SUCCESS: only show for the final requested depth (depth 0 normally, or --meta-level if specified)
      val isOutermostExecution = executionNestingDepth.get() == 1
      val hasFailures = rootFailedCount.get() > 0
      val showFinalStatus = isOutermostExecution && (hasFailures || isFinalDepth)
      logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix(completed = showFinalStatus))

      logger.prompt.clearPromptStatuses()

      val finishedOptsMap = (nonExclusiveResults ++ exclusiveResults).toMap

      // Convert versionMismatchReasons to Map[String, String] for InvalidationForest
      val taskInvalidationReasons = {
        import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala
        versionMismatchReasons.asScala.collect {
          case (t: Task.Named[?], reason) => t.ctx.segments.render -> reason
        }.toMap
      }

      ExecutionLogs.logInvalidationTree(
        interGroupDeps = interGroupDeps,
        outPath = outPath,
        runArtifacts = runArtifacts,
        uncached = uncached,
        changedValueHash = changedValueHash,
        spanningInvalidationTree = spanningInvalidationTree,
        taskInvalidationReasons = taskInvalidationReasons
      )

      val results0: Array[(Task[?], ExecResult[(Val, Int)])] = indexToTerminal
        .map { t =>
          finishedOptsMap(t) match {
            case None => (t, ExecResult.Skipped)
            case Some(res) =>
              Tuple2(
                t,
                (Seq(t) ++ plan.sortedGroups.lookupKey(t))
                  .flatMap { t0 => res.newResults.get(t0) }
                  .sortBy(!_.isInstanceOf[ExecResult.Failing[?]])
                  .head
              )

          }
        }

      val results: Map[Task[?], ExecResult[(Val, Int)]] = results0.toMap

      import scala.collection.JavaConverters.*
      Execution.Results(
        goals.toIndexedSeq.map(results(_).map(_._1)),
        finishedOptsMap.values.flatMap(_.toSeq.flatMap(_.newEvaluated)).toSeq,
        results.map { case (k, v) => (k, v.map(_._1)) },
        prefixes.asScala.toMap
      )

      } finally tracker.drain()
    }
  }

  def close(): Unit = {
    profileLogger.close()
  }
}

object Execution {

  /**
   * Per-execute0-call lease retention sink. Threading: `retain` and
   * `onCompleted` are called from many futures in parallel; `drain` is
   * called from the execute0 thread after all futures have completed.
   */
  trait LeaseTracker {
    def retain(task: Task[?], lease: LauncherLocking.Lease): Unit
    def onCompleted(terminal: Task[?]): Unit
    def drain(): Unit
  }

  /**
   * Format a failed count as a string to be used in status messages.
   * When completed: returns ", N FAILED" if count > 0, otherwise ", SUCCESS"
   * When in-progress: returns ", N failing" if count > 0, otherwise an empty string.
   */
  def formatFailedCount(
      failures: Int,
      completed: Boolean,
      errorColor: String => String,
      successColor: String => String
  ): fansi.Str = {
    (completed, failures) match {
      case (false, 0) => ""
      case (false, _) => ", " + errorColor(s"$failures failing")
      case (true, 0) => ", " + successColor("SUCCESS")
      case (true, _) => ", " + errorColor(s"$failures FAILED")
    }
  }

  def findInterGroupDeps(sortedGroups: MultiBiMap[Task[?], Task[?]])
      : Map[Task[?], Seq[Task[?]]] = {
    val out = Map.newBuilder[Task[?], Seq[Task[?]]]
    for ((terminal, group) <- sortedGroups) {
      val groupSet = group.toSet
      out.addOne(
        terminal -> groupSet
          .flatMap(
            _.inputs.collect { case f if !groupSet.contains(f) => sortedGroups.lookupValue(f) }
          )
          .toVector
          .sortBy(_.toString)
      )
    }
    out.result()
  }
  private[Execution] case class Results(
      results: Seq[ExecResult[Val]],
      uncached: Seq[Task[?]],
      transitiveResults: Map[Task[?], ExecResult[Val]],
      override val transitivePrefixes: Map[Task[?], Seq[String]]
  ) extends mill.api.ExecutionResults
}
