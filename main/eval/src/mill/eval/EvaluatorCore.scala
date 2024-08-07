package mill.eval

import mill.api.Result.{Aborted, Failing}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.eval.Evaluator.TaskResult
import mill.util._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.concurrent._

/**
 * Core logic of evaluating tasks, without any user-facing helper methods
 */
private[mill] trait EvaluatorCore extends GroupEvaluator {

  def baseLogger: ColorLogger

  /**
   * @param goals The tasks that need to be evaluated
   * @param reporter A function that will accept a module id and provide a listener for build problems in that module
   * @param testReporter Listener for test events like start, finish with success/error
   */
  def evaluate(
      goals: Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger
  ): Evaluator.Results = {
    os.makeDir.all(outPath)

    PathRef.validatedPaths.withValue(new PathRef.ValidatedPaths()) {
      val ec =
        if (effectiveThreadCount == 1) ExecutionContexts.RunNow
        else new ExecutionContexts.ThreadPool(effectiveThreadCount)

      def contextLoggerMsg(threadId: Int) =
        if (effectiveThreadCount == 1) ""
        else s"[#${if (effectiveThreadCount > 9) f"$threadId%02d" else threadId}] "

      try evaluate0(goals, logger, reporter, testReporter, ec, contextLoggerMsg)
      finally ec.close()
    }
  }

  private def getFailing(
      sortedGroups: MultiBiMap[Terminal, Task[_]],
      results: Map[Task[_], Evaluator.TaskResult[(Val, Int)]]
  ): MultiBiMap.Mutable[Terminal, Failing[Val]] = {
    val failing = new MultiBiMap.Mutable[Terminal, Result.Failing[Val]]
    for ((k, vs) <- sortedGroups.items()) {
      val failures = vs.items.flatMap(results.get).collect {
        case Evaluator.TaskResult(f: Result.Failing[(Val, Int)], _) => f.map(_._1)
      }

      failing.addAll(k, Loose.Agg.from(failures))
    }
    failing
  }

  private def evaluate0(
      goals: Agg[Task[_]],
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      ec: ExecutionContext with AutoCloseable,
      contextLoggerMsg0: Int => String
  ): Evaluator.Results = {
    os.makeDir.all(outPath)
    val chromeProfileLogger = new ChromeProfileLogger(outPath / "mill-chrome-profile.json")
    val profileLogger = new ProfileLogger(outPath / "mill-profile.json")
    val threadNumberer = new ThreadNumberer()
    val (sortedGroups, transitive) = Plan.plan(goals)
    val interGroupDeps = findInterGroupDeps(sortedGroups)
    val terminals0 = sortedGroups.keys().toVector
    val failed = new AtomicBoolean(false)
    val count = new AtomicInteger(1)

    val futures = mutable.Map.empty[Terminal, Future[Option[GroupEvaluator.Results]]]

    def evaluateTerminals(terminals: Seq[Terminal], contextLoggerMsg: Int => String)(implicit
        ec: ExecutionContext
    ) = {
      // We walk the task graph in topological order and schedule the futures
      // to run asynchronously. During this walk, we store the scheduled futures
      // in a dictionary. When scheduling each future, we are guaranteed that the
      // necessary upstream futures will have already been scheduled and stored,
      // due to the topological order of traversal.
      for (terminal <- terminals) {
        val deps = interGroupDeps(terminal)
        futures(terminal) = Future.sequence(deps.map(futures)).map { upstreamValues =>
          if (failed.get()) None
          else {
            val upstreamResults = upstreamValues
              .iterator
              .flatMap(_.iterator.flatMap(_.newResults))
              .toMap

            val startTime = System.nanoTime() / 1000
            val threadId = threadNumberer.getThreadId(Thread.currentThread())
            val counterMsg = s"${count.getAndIncrement()}/${terminals.size}"
            val contextLogger = PrefixLogger(
              out = logger,
              context = contextLoggerMsg(threadId),
              tickerContext = GroupEvaluator.dynamicTickerPrefix.value
            )

            val res = evaluateGroupCached(
              terminal = terminal,
              group = sortedGroups.lookupKey(terminal),
              results = upstreamResults,
              counterMsg = counterMsg,
              zincProblemReporter = reporter,
              testReporter = testReporter,
              logger = contextLogger
            )

            if (failFast && res.newResults.values.exists(_.result.asSuccess.isEmpty))
              failed.set(true)

            val endTime = System.nanoTime() / 1000

            val duration = endTime - startTime

            chromeProfileLogger.log(
              task = Terminal.printTerm(terminal),
              cat = "job",
              startTime = startTime,
              duration = duration,
              threadId = threadNumberer.getThreadId(Thread.currentThread()),
              cached = res.cached
            )

            profileLogger.log(
              ProfileLogger.Timing(
                terminal.render,
                (duration / 1000).toInt,
                res.cached,
                deps.map(_.render),
                res.inputsHash,
                res.previousInputsHash
              )
            )

            Some(res)
          }
        }
      }
    }

    val tasks0 = terminals0.filter {
      case Terminal.Labelled(c: Command[_], _) => false
      case _ => true
    }

    val (_, tasksTransitive0) = Plan.plan(Agg.from(tasks0.map(_.task)))

    val tasksTransitive = tasksTransitive0.toSet
    val (tasks, leafCommands) = terminals0.partition {
      case Terminal.Labelled(t, _) if tasksTransitive.contains(t) => true
      case _ => false
    }

    // Run all non-command tasks according to the threads
    // given but run the commands in linear order
    evaluateTerminals(
      tasks,
      // We want to skip the non-deterministic thread prefix in our test suite
      // since all it would do is clutter the testing logic trying to match on it
      if (sys.env.contains("MILL_TEST_SUITE")) _ => ""
      else contextLoggerMsg0
    )(ec)
    evaluateTerminals(leafCommands, _ => "")(ExecutionContexts.RunNow)

    val finishedOptsMap = terminals0
      .map(t => (t, Await.result(futures(t), duration.Duration.Inf)))
      .toMap

    val results0: Vector[(Task[_], TaskResult[(Val, Int)])] = terminals0
      .flatMap { t =>
        sortedGroups.lookupKey(t).flatMap { t0 =>
          finishedOptsMap(t) match {
            case None => Some((t0, TaskResult(Aborted, () => Aborted)))
            case Some(res) => res.newResults.get(t0).map(r => (t0, r))
          }
        }
      }

    val results: Map[Task[_], TaskResult[(Val, Int)]] = results0.toMap

    chromeProfileLogger.close()
    profileLogger.close()

    EvaluatorCore.Results(
      goals.indexed.map(results(_).map(_._1).result),
      // result of flatMap may contain non-distinct entries,
      // so we manually clean it up before converting to a `Strict.Agg`
      // see https://github.com/com-lihaoyi/mill/issues/2958
      Strict.Agg.from(
        finishedOptsMap.values.flatMap(_.toSeq.flatMap(_.newEvaluated)).iterator.distinct
      ),
      transitive,
      getFailing(sortedGroups, results),
      results.map { case (k, v) => (k, v.map(_._1)) }
    )
  }

  private def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]])
      : Map[Terminal, Seq[Terminal]] = {
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
}

private[mill] object EvaluatorCore {

  case class Results(
      rawValues: Seq[Result[Val]],
      evaluated: Agg[Task[_]],
      transitive: Agg[Task[_]],
      failing: MultiBiMap[Terminal, Result.Failing[Val]],
      results: Map[Task[_], TaskResult[Val]]
  ) extends Evaluator.Results
}
