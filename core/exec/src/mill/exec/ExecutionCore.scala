package mill.exec

import mill.api.Result.{Aborted, Failing}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.internal.{PrefixLogger, MultiBiMap}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.concurrent._

/**
 * Core logic of evaluating tasks, without any user-facing helper methods
 */
private[mill] trait ExecutionCore extends GroupExecution {

  def baseLogger: ColorLogger
  protected[exec] def chromeProfileLogger: ChromeProfileLogger
  protected[exec] def profileLogger: ProfileLogger

  /**
   * @param goals The tasks that need to be evaluated
   * @param reporter A function that will accept a module id and provide a listener for build problems in that module
   * @param testReporter Listener for test events like start, finish with success/error
   */
  def evaluate(
      goals: Agg[Task[?]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger,
      serialCommandExec: Boolean = false
  ): ExecResults = logger.withPromptUnpaused {
    os.makeDir.all(outPath)

    PathRef.validatedPaths.withValue(new PathRef.ValidatedPaths()) {
      val ec =
        if (effectiveThreadCount == 1) ExecutionContexts.RunNow
        else new ExecutionContexts.ThreadPool(effectiveThreadCount)

      try evaluate0(goals, logger, reporter, testReporter, ec, serialCommandExec)
      finally ec.close()
    }
  }

  private def getFailing(
      sortedGroups: MultiBiMap[Task[?], Task[?]],
      results: Map[Task[?], TaskResult[(Val, Int)]]
  ): MultiBiMap.Mutable[Task[?], Failing[Val]] = {
    val failing = new MultiBiMap.Mutable[Task[?], Result.Failing[Val]]
    for ((k, vs) <- sortedGroups.items()) {
      val failures = vs.items.flatMap(results.get).collect {
        case TaskResult(f: Result.Failing[(Val, Int)], _) => f.map(_._1)
      }

      failing.addAll(k, Loose.Agg.from(failures))
    }
    failing
  }

  private def evaluate0(
      goals: Agg[Task[?]],
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      ec: mill.api.Ctx.Fork.Impl,
      serialCommandExec: Boolean
  ): ExecResults = {
    os.makeDir.all(outPath)

    val threadNumberer = new ThreadNumberer()
    val plan = Plan.plan(goals)
    val interGroupDeps = ExecutionCore.findInterGroupDeps(plan.sortedGroups)
    val terminals0 = plan.sortedGroups.keys().toVector
    val failed = new AtomicBoolean(false)
    val count = new AtomicInteger(1)
    val indexToTerminal = plan.sortedGroups.keys().toArray

    ExecutionLogs.logDependencyTree(interGroupDeps, indexToTerminal, outPath)

    // Prepare a lookup tables up front of all the method names that each class owns,
    // and the class hierarchy, so during evaluation it is cheap to look up what class
    // each target belongs to determine of the enclosing class code signature changed.
    val (classToTransitiveClasses, allTransitiveClassMethods) =
      CodeSigUtils.precomputeMethodNamesPerClass(Plan.transitiveNamed(goals))

    val uncached = new ConcurrentHashMap[Task[?], Unit]()
    val changedValueHash = new ConcurrentHashMap[Task[?], Unit]()

    val futures = mutable.Map.empty[Task[?], Future[Option[GroupExecution.Results]]]

    def evaluateTerminals(
        terminals: Seq[Task[?]],
        forkExecutionContext: mill.api.Ctx.Fork.Impl,
        exclusive: Boolean
    ) = {
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

        if (!terminal.isExclusiveCommand && exclusiveDeps.nonEmpty) {
          val failure = Result.Failure(
            s"Non-exclusive task ${terminal} cannot depend on exclusive task " +
              exclusiveDeps.mkString(", ")
          )
          val taskResults = group
            .map(t => (t, TaskResult[(Val, Int)](failure, () => failure)))
            .toMap

          futures(terminal) = Future.successful(
            Some(GroupExecution.Results(taskResults, group.toSeq, false, -1, -1, false))
          )
        } else {
          futures(terminal) = Future.sequence(deps.map(futures)).map { upstreamValues =>
            try {
              val countMsg = mill.internal.Util.leftPad(
                count.getAndIncrement().toString,
                terminals.length.toString.length,
                '0'
              )

              val verboseKeySuffix = s"/${terminals0.size}"
              logger.setPromptHeaderPrefix(s"$countMsg$verboseKeySuffix")
              if (failed.get()) None
              else {
                val upstreamResults = upstreamValues
                  .iterator
                  .flatMap(_.iterator.flatMap(_.newResults))
                  .toMap

                val startTime = System.nanoTime() / 1000

                // should we log progress?
                val inputResults = for {
                  target <- group.indexed.filterNot(upstreamResults.contains)
                  item <- target.inputs.filterNot(group.contains)
                } yield upstreamResults(item).map(_._1)
                val logRun = inputResults.forall(_.result.isInstanceOf[Result.Success[?]])

                val tickerPrefix = if (logRun && logger.enableTicker) terminal.toString else ""

                val contextLogger = new PrefixLogger(
                  logger0 = logger,
                  key0 = if (!logger.enableTicker) Nil else Seq(countMsg),
                  verboseKeySuffix = verboseKeySuffix,
                  message = tickerPrefix,
                  noPrefix = exclusive
                )

                val res = evaluateGroupCached(
                  terminal = terminal,
                  group = plan.sortedGroups.lookupKey(terminal),
                  results = upstreamResults,
                  countMsg = countMsg,
                  verboseKeySuffix = verboseKeySuffix,
                  zincProblemReporter = reporter,
                  testReporter = testReporter,
                  logger = contextLogger,
                  classToTransitiveClasses,
                  allTransitiveClassMethods,
                  forkExecutionContext,
                  exclusive
                )

                if (failFast && res.newResults.values.exists(_.result.asSuccess.isEmpty))
                  failed.set(true)

                val endTime = System.nanoTime() / 1000
                val duration = endTime - startTime

                val threadId = threadNumberer.getThreadId(Thread.currentThread())
                chromeProfileLogger.log(terminal, "job", startTime, duration, threadId, res.cached)

                if (!res.cached) uncached.put(terminal, ())
                if (res.valueHashChanged) changedValueHash.put(terminal, ())

                profileLogger.log(terminal, duration, res, deps)

                Some(res)
              }
            } catch {
              case e: Throwable if !scala.util.control.NonFatal(e) =>
                throw new Exception(e)
            }
          }
        }
      }

      // Make sure we wait for all tasks from this batch to finish before starting the next
      // one, so we don't mix up exclusive and non-exclusive tasks running at the same time
      terminals.map(t => (t, Await.result(futures(t), duration.Duration.Inf)))
    }

    val tasks0 = terminals0.filter {
      case c: Command[_] => false
      case _ => true
    }

    val tasksTransitive = Plan.transitiveTargets(Agg.from(tasks0)).toSet
    val (tasks, leafExclusiveCommands) = terminals0.partition {
      case t: NamedTask[_] => tasksTransitive.contains(t) || !t.isExclusiveCommand
      case _ => !serialCommandExec
    }

    // Run all non-command tasks according to the threads
    // given but run the commands in linear order
    val nonExclusiveResults = evaluateTerminals(tasks, ec, exclusive = false)

    val exclusiveResults = evaluateTerminals(leafExclusiveCommands, ec, exclusive = true)

    logger.clearPromptStatuses()

    val finishedOptsMap = (nonExclusiveResults ++ exclusiveResults).toMap

    ExecutionLogs.logInvalidationTree(
      interGroupDeps,
      indexToTerminal,
      outPath,
      uncached,
      changedValueHash
    )

    val results0: Vector[(Task[?], TaskResult[(Val, Int)])] = terminals0
      .flatMap { t =>
        plan.sortedGroups.lookupKey(t).flatMap { t0 =>
          finishedOptsMap(t) match {
            case None => Some((t0, TaskResult(Aborted, () => Aborted)))
            case Some(res) => res.newResults.get(t0).map(r => (t0, r))
          }
        }
      }

    val results: Map[Task[?], TaskResult[(Val, Int)]] = results0.toMap

    ExecutionCore.Results(
      goals.indexed.map(results(_).map(_._1).result),
      // result of flatMap may contain non-distinct entries,
      // so we manually clean it up before converting to a `Strict.Agg`
      // see https://github.com/com-lihaoyi/mill/issues/2958
      Strict.Agg.from(
        finishedOptsMap.values.flatMap(_.toSeq.flatMap(_.newEvaluated)).iterator.distinct
      ),
      getFailing(plan.sortedGroups, results).items().map { case (k, v) => (k, v.toSeq) }.toMap,
      results.map { case (k, v) => (k, v.map(_._1)) }
    )
  }
}

private[mill] object ExecutionCore {
  def findInterGroupDeps(sortedGroups: MultiBiMap[Task[?], Task[?]])
      : Map[Task[?], Seq[Task[?]]] = {
    sortedGroups
      .items()
      .map { case (terminal, group) =>
        terminal -> Seq.from(group)
          .flatMap(_.inputs)
          .filterNot(group.contains)
          .distinct
          .map(sortedGroups.lookupValue)
          .distinct
      }
      .toMap
  }
  case class Results(
      rawValues: Seq[Result[Val]],
      evaluated: Agg[Task[?]],
      failing: Map[Task[?], Seq[Result.Failing[Val]]],
      results: Map[Task[?], TaskResult[Val]]
  ) extends ExecResults
}
