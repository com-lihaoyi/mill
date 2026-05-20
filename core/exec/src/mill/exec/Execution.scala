package mill.exec

import mill.api.daemon.internal.*
import mill.api.daemon.internal.{LauncherLocking, LauncherOutFiles}
import mill.api.*
import mill.internal.{CodeSigUtils, JsonArrayLogger, PrefixLogger, PromptWaitReporter}

import java.util.concurrent.{ConcurrentHashMap, ThreadPoolExecutor}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.concurrent.*
import scala.util.{Failure, Success}

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
    versionMismatchReasons: ConcurrentHashMap[Task[?], String] = ConcurrentHashMap()
) extends GroupExecution with AutoCloseable {

  // Track nesting depth of executeTasks calls to only show final status on outermost call
  private val executionNestingDepth = AtomicInteger(0)

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
    // Only depth=0 (the user build) publishes through `runArtifacts`, so meta-build
    // levels must write to their own per-depth `outPath / mill-profile.json` to avoid
    // multiple Executions racing on the same file and producing a corrupted profile.
    profileLogger = new JsonArrayLogger.Profile(
      if (depth == 0) os.Path(runArtifacts.profile)
      else os.Path(outPath) / mill.constants.OutFiles.millProfile
    ),
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
    } finally {
      executionNestingDepth.decrementAndGet()
    }
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
    val failed = AtomicBoolean(false)
    val count = AtomicInteger(1)
    val completedCount = AtomicInteger(0)
    val rootFailedCount = AtomicInteger(0) // Track only root failures
    val planningLogger = PrefixLogger(
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
      val plan = PlanImpl.plan(goals, effectiveInputs)
      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups, effectiveInputs)
      val indexToTerminal = plan.sortedGroups.keys().toArray
      ExecutionLogs.logDependencyTree(
        interGroupDeps,
        indexToTerminal,
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

      val tasksTransitive =
        PlanImpl.transitiveTasks(Seq.from(indexToTerminal), effectiveInputs).toSet
      val downstreamEdges: Map[Task[?], Set[Task[?]]] =
        tasksTransitive.flatMap(t => effectiveInputs(t).map(_ -> t)).groupMap(_._1)(_._2)

      val allExclusiveCommands = tasksTransitive.filter(_.isExclusiveCommand)
      val downstreamOfExclusive =
        mill.internal.SpanningForest.breadthFirst[Task[?]](allExclusiveCommands)(t =>
          downstreamEdges.getOrElse(t, Set())
        )

      val tracker = new Execution.LeaseTracker(
        indexToTerminal,
        interGroupDeps
      )

      def evaluateTerminals(
          terminals: Seq[Task[?]],
          exclusive: Boolean
      ) = {
        val forkExecutionContext =
          ec.fold(ExecutionContexts.RunNow)(ExecutionContexts.ThreadPool(_))
        implicit val taskExecutionContext =
          if (exclusive) ExecutionContexts.RunNow else forkExecutionContext
        for (terminal <- terminals) {
          val deps = interGroupDeps(terminal)

          val group = plan.sortedGroups.lookupKey(terminal).toSeq
          val exclusiveDeps = deps.filter(d => d.isExclusiveCommand)

          if (terminal.asCommand.isEmpty && downstreamOfExclusive.contains(terminal)) {
            val failure = ExecResult.Failure(
              s"Non-Command task ${terminal} cannot depend on exclusive command " +
                exclusiveDeps.mkString(", ")
            )
            val taskResults: Map[Task[?], ExecResult.Failing[Nothing]] = group
              .map(t => (t, failure))
              .toMap

            tracker.onCompleted(terminal)
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
            val raw = Future.sequence(deps.map(futures)).map {
              upstreamValues =>
                try {
                  val countMsg = mill.api.internal.Util.leftPad(
                    count.getAndIncrement().toString,
                    terminals.length.toString.length,
                    '0'
                  )

                  val contextLogger = PrefixLogger(
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
                      val upstreamResults =
                        mutable.HashMap.empty[Task[?], ExecResult[(Val, Int)]]
                      val upstreamPathRefs = mutable.ArrayBuffer.empty[PathRef]
                      upstreamValues.foreach {
                        case Some(results) =>
                          upstreamResults.addAll(results.newResults)
                          upstreamPathRefs.addAll(results.serializedPaths)
                        case None =>
                      }

                      val startTime = System.nanoTime() / 1000

                      val res = executeGroupCached(
                        terminal = terminal,
                        group = group,
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

                      // Skipped (upstream-failed) tasks are not counted as new failures.
                      val newFailures = res.newResults.values.count(r => r.asFailing.isDefined)

                      rootFailedCount.addAndGet(newFailures)
                      completedCount.incrementAndGet()

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
                  // Controlled shutdown signal; let it propagate.
                  case e: mill.api.daemon.StopWithResponse[?] => throw e
                  // Wrap fatal so the Future infrastructure catches it; otherwise
                  // downstream Awaits hang on a silently-terminated future.
                  case e: Throwable if !mill.api.daemon.internal.NonFatal(e) =>
                    val nonFatal = Exception(s"fatal exception occurred: $e", e)
                    nonFatal.setStackTrace(e.getStackTrace)
                    throw nonFatal
                }
            }
            // andThen so onCompleted fires even when an upstream throw
            // skips the .map body; otherwise transitive downstream
            // pending counts and Read leases leak until tracker.drain().
            futures(terminal) = raw.andThen { case _ => tracker.onCompleted(terminal) }
          }
        }

        // Wait for every future to settle (Try-wrapped, not fail-fast)
        // so the outer `tracker.drain()` finally can't race a sibling
        // still mid-`retain`/`onCompleted`; first failure rethrown after.
        val settled = Future.sequence(
          terminals.map(t => futures(t).transform(r => Success(t -> r)))
        )
        Await.result(settled, duration.Duration.Inf).map {
          case (t, Success(v)) => (t, v)
          case (_, Failure(e)) => throw e
        }
      }

      try {
        val (nonExclusiveTasks, leafExclusiveCommands) = indexToTerminal.partition {
          case t: Task.Named[_] => !downstreamOfExclusive.contains(t)
          case _ => !serialCommandExec
        }

        val batchWaitReporter =
          PromptWaitReporter.fromLogger(logger, baseLogger.streams.err)
        def withExclusiveLease[A](kind: LauncherLocking.LockKind)(body: => A): A = {
          val lease = workspaceLocking.exclusiveLock(kind, batchWaitReporter)
          try body
          finally lease.close()
        }

        // Whole-batch Write rather than splitting Read/Write across phases:
        // splitting would let a peer rewrite an upstream `dest` between
        // our Read-drain and Write-acquire, racing our retained-Read
        // invariants and corrupting downstream consumers.
        // Only `globalExclusive` commands need the daemon-wide Write lock;
        // plain `exclusive` commands still serialize within this batch but
        // can overlap with other launchers' tasks.
        val haveExclusive = leafExclusiveCommands.nonEmpty
        val haveGlobalExclusive = leafExclusiveCommands.exists(_.isGlobalExclusiveCommand)
        val outerKind =
          if (haveGlobalExclusive) LauncherLocking.LockKind.Write
          else LauncherLocking.LockKind.Read

        // Suspend any outer-batch exclusive lease this launcher already
        // holds so a nested call (e.g. from `show`'s body invoking
        // `Evaluator.execute`) can take its own without self-deadlocking on
        // the non-reentrant underlying lock. No-op when nothing is held.
        val empty = Seq.empty[(Task[?], Option[GroupExecution.Results])]

        def runBatch(): (
            Seq[(Task[?], Option[GroupExecution.Results])],
            Seq[(Task[?], Option[GroupExecution.Results])]
        ) = {
          val ne =
            if (nonExclusiveTasks.isEmpty) empty
            else evaluateTerminals(nonExclusiveTasks, exclusive = false).toSeq
          val ex = leafExclusiveCommands.flatMap { terminal =>
            evaluateTerminals(Seq(terminal), exclusive = true)
          }.toSeq
          (ne, ex)
        }

        val (nonExclusiveResults, exclusiveResults) =
          if (nonExclusiveTasks.isEmpty && !haveExclusive) (empty, empty)
          else workspaceLocking.withReleasedExclusive(batchWaitReporter)(
            withExclusiveLease(outerKind)(runBatch())
          )

        // FAILED on any outermost failure (meta-build failure aborts bootstrap);
        // SUCCESS only at the final requested depth.
        val isOutermostExecution = executionNestingDepth.get() == 1
        val hasFailures = rootFailedCount.get() > 0
        val showFinalStatus = isOutermostExecution && (hasFailures || isFinalDepth)
        logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix(completed = showFinalStatus))

        logger.prompt.clearPromptStatuses()

        val finishedOptsMap = (nonExclusiveResults ++ exclusiveResults).toMap

        val taskInvalidationReasons = {
          import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala
          versionMismatchReasons.asScala.collect {
            case (t: Task.Named[?], reason) => t.ctx.segments.render -> reason
          }.toMap
        }

        ExecutionLogs.logInvalidationTree(
          interGroupDeps = interGroupDeps,
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
                var first: ExecResult[(Val, Int)] = null
                var failing: ExecResult[(Val, Int)] = null
                def observe(result: ExecResult[(Val, Int)]): Unit = {
                  if (first == null) first = result
                  if (failing == null && result.asFailing.isDefined) failing = result
                }
                res.newResults.get(t).foreach(observe)
                val group = plan.sortedGroups.lookupKey(t)
                group.foreach(t0 => res.newResults.get(t0).foreach(observe))

                if (first == null) {
                  throw new NoSuchElementException(s"No result found for $t")
                }
                Tuple2(t, if (failing == null) first else failing)

            }
          }

        val results: Map[Task[?], ExecResult[(Val, Int)]] = results0.toMap

        import scala.jdk.CollectionConverters.*
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
   * Tracks per-task read leases on the workspace lock and releases them once
   * every transitive downstream task that depends on the holder has completed.
   *
   * The transitive part matters because a direct downstream can forward
   * PathRefs or other data from an upstream output to its own downstreams.
   * Releasing the upstream read lease when only the direct downstream completes
   * would let a concurrent launcher overwrite that output while a later
   * transitive downstream may still read it.
   */
  class LeaseTracker(
      indexToTerminal: Array[Task[?]],
      interGroupDeps: Map[Task[?], Seq[Task[?]]]
  ) {
    class State(initialPending: Int) {
      val pending = AtomicInteger(initialPending)
      val completed = AtomicBoolean(false)
      val leases = new java.util.concurrent.ConcurrentLinkedQueue[LauncherLocking.Lease]()
    }

    val states = new ConcurrentHashMap[Task[?], State]()

    locally {
      val pendingCounts = mutable.Map.empty[Task[?], Int].withDefaultValue(0)
      for ((_, upstreams) <- interGroupDeps; up <- upstreams)
        pendingCounts(up) = pendingCounts(up) + 1
      for (t <- indexToTerminal) states.put(t, State(pendingCounts.getOrElse(t, 0)))
    }

    private def closeQuietly(lease: LauncherLocking.Lease): Unit =
      try lease.close()
      catch { case _: Throwable => () }

    private def drainLeases(s: State): Unit = {
      var lease = s.leases.poll()
      while (lease != null) {
        closeQuietly(lease)
        lease = s.leases.poll()
      }
    }

    private def releaseIfDrained(start: Task[?]): Unit = {
      val queue = mutable.Queue(start)
      while (queue.nonEmpty) {
        val task = queue.dequeue()
        val s = states.get(task)
        if (s != null && s.completed.get() && s.pending.get() == 0 && states.remove(task, s)) {
          drainLeases(s)
          for (upstream <- interGroupDeps.getOrElse(task, Nil)) {
            val upstreamState = states.get(upstream)
            if (upstreamState != null) {
              upstreamState.pending.decrementAndGet()
              queue.enqueue(upstream)
            }
          }
        }
      }
    }

    def retain(task: Task[?], lease: LauncherLocking.Lease): Unit = {
      val s = states.get(task)
      if (s != null) s.leases.add(lease)
      else closeQuietly(lease)
    }

    def onCompleted(terminal: Task[?]): Unit = {
      val own = states.get(terminal)
      if (own != null) {
        own.completed.set(true)
        releaseIfDrained(terminal)
      }
    }

    def drain(): Unit = {
      import scala.jdk.CollectionConverters.*
      states.keys().asScala.toList.foreach { task =>
        val s = states.remove(task)
        if (s != null) drainLeases(s)
      }
    }
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

  def findInterGroupDeps(
      sortedGroups: MultiBiMap[Task[?], Task[?]],
      effectiveInputs: Task[?] => Seq[Task[?]] = _.inputs
  ): Map[Task[?], Seq[Task[?]]] = {
    val out = Map.newBuilder[Task[?], Seq[Task[?]]]
    val terminalOrder = sortedGroups.keys().zipWithIndex.toMap
    for ((terminal, group) <- sortedGroups) {
      val groupSet = group.toSet
      val deps = mutable.LinkedHashSet.empty[Task[?]]
      for {
        task <- group
        input <- effectiveInputs(task)
        if !groupSet.contains(input)
      } input match {
        // Anonymous tasks aren't deterministic group cut-points
        // (see PlanImpl.plan), so a non-Named upstream escaping here
        // means grouping is wrong; fail loudly rather than wire the
        // dep to an arbitrary group.
        case f: Task.Named[?] => deps += f
        case f =>
          throw new AssertionError(
            s"Non-Named external dependency $f escaping group of $terminal; " +
              s"groupAroundImportantTasks must cut at every named task."
          )
      }
      out.addOne(
        terminal -> deps.toVector.sortBy(t => terminalOrder.getOrElse(t, Int.MaxValue))
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
