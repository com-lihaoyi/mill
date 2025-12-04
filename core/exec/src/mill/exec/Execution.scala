package mill.exec

import mill.api.daemon.internal.*
import mill.constants.OutFiles.OutFiles.millProfile
import mill.api.*
import mill.internal.{JsonArrayLogger, PrefixLogger}

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
    workerCache: mutable.Map[String, (Int, Val)],
    env: Map[String, String],
    failFast: Boolean,
    ec: Option[ThreadPoolExecutor],
    codeSignatures: Map[String, Int],
    systemExit: ( /* reason */ String, /* exitCode */ Int) => Nothing,
    exclusiveSystemStreams: SystemStreams,
    getEvaluator: () => EvaluatorApi,
    offline: Boolean,
    staticBuildOverrideFiles: Map[java.nio.file.Path, String],
    enableTicker: Boolean
) extends GroupExecution with AutoCloseable {

  // this (shorter) constructor is used from [[MillBuildBootstrap]] via reflection
  def this(
      baseLogger: Logger,
      workspace: java.nio.file.Path,
      outPath: java.nio.file.Path,
      externalOutPath: java.nio.file.Path,
      rootModule: BaseModuleApi,
      classLoaderSigHash: Int,
      classLoaderIdentityHash: Int,
      workerCache: mutable.Map[String, (Int, Val)],
      env: Map[String, String],
      failFast: Boolean,
      ec: Option[ThreadPoolExecutor],
      codeSignatures: Map[String, Int],
      systemExit: ( /* reason */ String, /* exitCode */ Int) => Nothing,
      exclusiveSystemStreams: SystemStreams,
      getEvaluator: () => EvaluatorApi,
      offline: Boolean,
      staticBuildOverrideFiles: Map[java.nio.file.Path, String],
      enableTicker: Boolean
  ) = this(
    baseLogger,
    new JsonArrayLogger.Profile(os.Path(outPath) / millProfile),
    os.Path(workspace),
    os.Path(outPath),
    os.Path(externalOutPath),
    rootModule,
    classLoaderSigHash,
    classLoaderIdentityHash,
    workerCache,
    env,
    failFast,
    ec,
    codeSignatures,
    systemExit,
    exclusiveSystemStreams,
    getEvaluator,
    offline,
    staticBuildOverrideFiles,
    enableTicker
  )

  def withBaseLogger(newBaseLogger: Logger) = this.copy(baseLogger = newBaseLogger)

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

    PathRef.validatedPaths.withValue(new PathRef.ValidatedPaths()) {
      execute0(goals, logger, reporter, testReporter, serialCommandExec)
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
      val plan = PlanImpl.plan(goals)
      val interGroupDeps = Execution.findInterGroupDeps(plan.sortedGroups)
      val indexToTerminal = plan.sortedGroups.keys().toArray
      ExecutionLogs.logDependencyTree(interGroupDeps, indexToTerminal, outPath)
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

      def formatHeaderPrefix(countMsg: String, keySuffix: String) =
        s"$countMsg$keySuffix${Execution.formatFailedCount(rootFailedCount.get(), logger.prompt.errorColor)}"

      val tasksTransitive = PlanImpl.transitiveTasks(Seq.from(indexToTerminal)).toSet
      val downstreamEdges: Map[Task[?], Set[Task[?]]] =
        tasksTransitive.flatMap(t => t.inputs.map(_ -> t)).groupMap(_._1)(_._2)

      val allExclusiveCommands = tasksTransitive.filter(_.isExclusiveCommand)
      val downstreamOfExclusive =
        mill.internal.SpanningForest.breadthFirst[Task[?]](allExclusiveCommands)(t =>
          downstreamEdges.getOrElse(t, Set())
        )

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

                val keySuffix = s"/${indexToTerminal.size}"

                val contextLogger = PrefixLogger(
                  logger0 = logger,
                  key0 = Seq(countMsg),
                  keySuffix = keySuffix,
                  message = terminal.toString,
                  noPrefix = exclusive
                )

                if (enableTicker) prefixes.put(terminal, contextLogger.logKey)
                contextLogger.withPromptLine {
                  logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix(countMsg, keySuffix))

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
                      upstreamPathRefs = upstreamPathRefs
                    )

                    // Count new failures - if there are upstream failures, tasks should be skipped, not failed
                    val newFailures = res.newResults.values.count(r => r.asFailing.isDefined)

                    rootFailedCount.addAndGet(newFailures)

                    // Always show failed count in header if there are failures
                    logger.prompt.setPromptHeaderPrefix(formatHeaderPrefix(countMsg, keySuffix))

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
                // Wrapping the fatal error in a non-fatal exception, so it would be caught by Scala's Future
                // infrastructure, rather than silently terminating the future and leaving downstream Awaits hanging.
                case e: Throwable if !scala.util.control.NonFatal(e) =>
                  val nonFatal = Exception(s"fatal exception occurred: $e", e)
                  // Set the stack trace of the non-fatal exception to the original exception's stack trace
                  // as it actually indicates the location of the error.
                  nonFatal.setStackTrace(e.getStackTrace)
                  throw nonFatal
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

      logger.prompt.clearPromptStatuses()

      val finishedOptsMap = (nonExclusiveResults ++ exclusiveResults).toMap

      ExecutionLogs.logInvalidationTree(
        interGroupDeps = interGroupDeps,
        indexToTerminal = indexToTerminal,
        outPath = outPath,
        uncached = uncached,
        changedValueHash = changedValueHash
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

      import scala.collection.JavaConverters._
      Execution.Results(
        goals.toIndexedSeq.map(results(_).map(_._1)),
        finishedOptsMap.values.flatMap(_.toSeq.flatMap(_.newEvaluated)).toSeq,
        results.map { case (k, v) => (k, v.map(_._1)) },
        prefixes.asScala.toMap
      )
    }
  }

  def close(): Unit = {
    profileLogger.close()
  }
}

object Execution {

  /**
   * Format a failed count as a string to be used in status messages.
   * Returns ", N failed" if count > 0, otherwise an empty string.
   */
  def formatFailedCount(count: Int, color: String => String): String = {
    if (count > 0) s", " + color(s"$count failed") else ""
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
          .toArray
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
