package mill.eval

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ExecutorCompletionService, Executors, Future}

import ammonite.runtime.SpecialClassLoader
import mill.api.Result.{Aborted, OuterStack, Success}
import mill.api.Strict.Agg
import mill.api.{BuildProblemReporter, DummyTestReporter, Strict, TestReporter}
import mill.define.{Ctx => _, _}
import mill.util
import mill.util.Router.EntryPoint
import mill.util._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

case class Labelled[T](task: NamedTask[T],
                       segments: Segments){
  def format = task match{
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer = task match{
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}

case class Evaluator(
  home: os.Path,
  outPath: os.Path,
  externalOutPath: os.Path,
  rootModule: mill.define.BaseModule,
  log: Logger,
  classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = Evaluator.classLoaderSig,
  workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty,
  env: Map[String, String] = Evaluator.defaultEnv,
  failFast: Boolean = true,
  threadCount: Option[Int] = None
) {

  val effectiveThreadCount: Int = this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  import Evaluator.Evaluated

  val classLoaderSignHash = classLoaderSig.hashCode()

  /**
    * @param goals The tasks that need to be evaluated
    * @param reporter A function that will accept a module id and provide a listener for build problems in that module
    * @param testReporter Listener for test events like start, finish with success/error
    */
  def evaluate(goals: Agg[Task[_]],
               reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
               testReporter: TestReporter = DummyTestReporter,
               logger: Logger = log): Evaluator.Results = {
    if(effectiveThreadCount > 1) {
      new ParallelEvaluator(goals, effectiveThreadCount, reporter, testReporter, logger).evaluate()
    } else {
    os.makeDir.all(outPath)
    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]
    var someTaskFailed: Boolean = false

    val timings = mutable.ArrayBuffer.empty[(Either[Task[_], Labelled[_]], Int, Boolean)]
    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex)
      if(failFast && someTaskFailed) {
        // we exit early and set aborted state for all left tasks
        group.foreach { task =>
          results.put(task, Aborted)
        }

      } else {

      val startTime = System.currentTimeMillis()
      // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
      val counterMsg = (i+1) + "/" + sortedGroups.keyCount
      val Evaluated(newResults, newEvaluated, cached) = evaluateGroupCached(
        terminal,
        group,
        results,
        counterMsg,
        reporter,
        testReporter,
        logger
      )
      someTaskFailed = someTaskFailed || newResults.exists(task => !task._2.isInstanceOf[Success[_]])

      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) {
        results.put(k, v)
      }
      val endTime = System.currentTimeMillis()

      timings.append((terminal, (endTime - startTime).toInt, cached))
    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing[_]]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(
        k,
        vs.items.flatMap(results.get).collect{case f: Result.Failing[_] => f.map(_._1)}
      )
    }
    Evaluator.writeTimings(timings, outPath)
    Evaluator.Results(
      goals.indexed.map(results(_).map(_._1)),
      evaluated,
      transitive,
      failing,
      timings,
      results.map{case (k, v) => (k, v.map(_._1))}
    )
   }
  }

  // those result which are inputs but not contained in this terminal group
  protected def evaluateGroupCached(terminal: Terminal,
    group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    counterMsg: String,
    zincProblemReporter: Int => Option[BuildProblemReporter],
    testReporter: TestReporter,
    logger: Logger
  ): Evaluated = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.toIterator.map(_.sideHash)
    )

    val inputsHash = externalInputsHash + sideHashes + classLoaderSignHash

    terminal match {
      case Left(task) =>
        val (newResults, newEvaluated) = evaluateGroup(
          group,
          results,
          inputsHash,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg,
          zincProblemReporter,
          testReporter,
          logger
        )
        Evaluated(newResults, newEvaluated, false)
      case Right(labelledNamedTask) =>

        val out = if (!labelledNamedTask.task.ctx.external) outPath
          else externalOutPath

        val paths = Evaluator.resolveDestPaths(
          out,
          destSegments(labelledNamedTask)
        )

        if (!os.exists(paths.out)) os.makeDir.all(paths.out)
        val cached = for{
          cached <-
            try Some(upickle.default.read[Evaluator.Cached](paths.meta.toIO))
            catch {case e: Throwable => None}

          if cached.inputsHash == inputsHash
          reader <- labelledNamedTask.format
          parsed <-
            try Some(upickle.default.read(cached.value)(reader))
            catch {case e: Throwable => None}
        } yield (parsed, cached.valueHash)

        val workerCached = labelledNamedTask.task.asWorker
          .flatMap{w => workerCache.get(w.ctx.segments)}
          .collect{case (`inputsHash`, v) => v}

        workerCached.map((_, inputsHash)) orElse cached match{
          case Some((v, hashCode)) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]
            newResults(labelledNamedTask.task) = Result.Success((v, hashCode))

            Evaluated(newResults, Nil, true)

          case _ =>
            val Seq(first, rest @_*) = labelledNamedTask.segments.value
            val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map{
              case Segment.Label(s) => "." + s
              case Segment.Cross(s) => "[" + s.mkString(",") + "]"
            }

            if (labelledNamedTask.task.flushDest) os.remove.all(paths.dest)

            val (newResults, newEvaluated) = evaluateGroup(
              group,
              results,
              inputsHash,
              paths = Some(paths),
              maybeTargetLabel = Some(msgParts.mkString),
              counterMsg = counterMsg,
              zincProblemReporter,
              testReporter,
              logger
            )

            newResults(labelledNamedTask.task) match{
              case Result.Failure(_, Some((v, hashCode))) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case Result.Success((v, hashCode)) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                os.remove.all(paths.meta)
            }

            Evaluated(newResults, newEvaluated, false)
        }
    }
  }

  def destSegments(labelledTask : Labelled[_]) : Segments = {
    labelledTask.task.ctx.foreign match {
      case Some(foreignSegments) =>
        foreignSegments ++ labelledTask.segments

      case None =>
        labelledTask.segments
    }
  }


  def handleTaskResult(v: Any,
                       hashCode: Int,
                       metaPath: os.Path,
                       inputsHash: Int,
                       labelledNamedTask: Labelled[_]) = {
    labelledNamedTask.task.asWorker match{
      case Some(w) => workerCache(w.ctx.segments) = (inputsHash, v)
      case None =>
        val terminalResult = labelledNamedTask
          .writer
          .asInstanceOf[Option[upickle.default.Writer[Any]]]
          .map(w => upickle.default.writeJs(v)(w) -> v)

        for((json, v) <- terminalResult){
          os.write.over(
            metaPath,
            upickle.default.stream(
              Evaluator.Cached(json, hashCode, inputsHash),
              indent = 4
            ),
            createFolders = true
          )
        }
    }
  }

  protected def evaluateGroup(group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    inputsHash: Int,
    paths: Option[Evaluator.Paths],
    maybeTargetLabel: Option[String],
    counterMsg: String,
    reporter: Int => Option[BuildProblemReporter],
    testReporter: TestReporter,
    logger: Logger
   ): (mutable.LinkedHashMap[Task[_], Result[(Any, Int)]], mutable.Buffer[Task[_]]) = PrintLogger.withContext(maybeTargetLabel.map(_ + ": ")) {

    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    var logEnd: Option[() => Unit] = None

    val tickerPrefix = maybeTargetLabel.map { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      val prefix = s"[$counterMsg] $targetLabel "
      if(logRun) {
        log.ticker(prefix)
        if(effectiveThreadCount > 1) logEnd = Some(() => log.ticker(prefix + "FINISHED"))
      }
      prefix + "| "
    }

    val multiLogger = new ProxyLogger(resolveLogger(paths.map(_.log), logger)) {
      override def ticker(s: String): Unit = {
        super.ticker(tickerPrefix.getOrElse("")+s)
      }
    }
    var usedDest = Option.empty[(Task[_], Array[StackTraceElement])]
    for (task <- nonEvaluatedTargets) {
      newEvaluated.append(task)
      val targetInputValues = task.inputs
        .map{x => newResults.getOrElse(x, results(x))}
        .collect{ case Result.Success((v, hashCode)) => v }

      val res =
        if (targetInputValues.length != task.inputs.length) Result.Skipped
        else {
          val args = new Ctx(
            targetInputValues.toArray[Any],
            () => usedDest match{
              case Some((earlierTask, earlierStack)) if earlierTask != task =>
                val inner = new Exception("Earlier usage of `dest`")
                inner.setStackTrace(earlierStack)
                throw new Exception(
                  "`dest` can only be used in one place within each Target[T]",
                  inner
                )
              case _ =>


                paths match{
                  case Some(dest) =>
                    if (usedDest.isEmpty) os.makeDir.all(dest.dest)
                    usedDest = Some((task, new Exception().getStackTrace))
                    dest.dest
                  case None =>
                    throw new Exception("No `dest` folder available here")
                }
            },
            multiLogger,
            home,
            env,
            reporter,
            testReporter
          ) with Ctx.Jobs {
            override def jobs: Int = effectiveThreadCount
          }

          val out = System.out
          val in = System.in
          val err = System.err
          try{
            System.setIn(multiLogger.inStream)
            System.setErr(multiLogger.errorStream)
            System.setOut(multiLogger.outputStream)
            Console.withIn(multiLogger.inStream){
              Console.withOut(multiLogger.outputStream){
                Console.withErr(multiLogger.errorStream){
                  try task.evaluate(args)
                  catch { case NonFatal(e) =>
                    Result.Exception(e, new OuterStack(new Exception().getStackTrace))
                  }
                }
              }
            }
          }finally{
            System.setErr(err)
            System.setOut(out)
            System.setIn(in)
          }
        }

      newResults(task) = for(v <- res) yield {
        (v,
          if (task.isInstanceOf[Worker[_]]) inputsHash
          else v.##
        )
      }
    }

    logEnd.foreach(_())

    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[os.Path], logger: Logger): Logger = logPath match{
    case None => logger
    case Some(path) => MultiLogger(logger.colored, log, new FileLogger(logger.colored, path, debugEnabled = true))
  }

  type Terminal = Either[Task[_], Labelled[Any]]
  type TerminalGroup = (Terminal, Agg[Task[_]])

  /**
    * This class encapsulates the whole execution logic of the multi-threaded task evaluator.
    *
    * @param goals The requested tasks to execute
    * @param threadCount The number of threads to use in parallel
    * @param reporter An optional reporter for build problems
    * @param testReporter An optional reporter for test events
    * @param logger The mill logger to report progress and errors
    */
  class ParallelEvaluator(
    goals: Agg[Task[_]],
    threadCount: Int,
    reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
    testReporter: TestReporter = DummyTestReporter,
    logger: Logger = log
  ) {
    logger.info(s"Using experimental parallel evaluator with ${threadCount} threads")

    case class FutureResult(task: TerminalGroup, time: Int, result: Evaluated)

    type Timing = (Either[Task[_], Labelled[_]], Int, Boolean)

    class State(sortedGroups: MultiBiMap[Either[Task[_], Labelled[Any]], Task[_]])(implicit evalLog: EvalLog) {
      ///////////////
      // VARIABLES

      // The unprocessed terminal groups
      private[ParallelEvaluator] var pending: List[TerminalGroup] = sortedGroups.items().toList
      // The currently scheduled (maybe not started yet) terminal groups
      private[ParallelEvaluator] var inProgress = List[TerminalGroup]()
      // The finished terminal groups
      private[ParallelEvaluator] var doneMap = Set[TerminalGroup]()

      // The scheduled and not yet finished futures (Java!)
      private[ParallelEvaluator] var scheduledFutures = Map[java.util.concurrent.Future[FutureResult], TerminalGroup]()

      ///////////////
      // MUTABLE
      // The fact that at least one task failed
      private[ParallelEvaluator] val someTaskFailed = new AtomicBoolean(false)
      // Mutable collector for timings
      private[ParallelEvaluator] val timings = new mutable.ArrayBuffer[Timing](pending.size)
      // Mutable collector for all task results
      private[ParallelEvaluator] val results = new ConcurrentHashMap[Task[_], Result[(Any, Int)]]()
      // Mutable collector for all evaluated tasks
      private[ParallelEvaluator] val evaluated = new Agg.Mutable[Task[_]]

      ///////////////

      private[ParallelEvaluator] val interGroupDeps: Map[TerminalGroup, Seq[TerminalGroup]] = {
        val startTime = System.currentTimeMillis()
        val res = findInterGroupDeps(sortedGroups)
        evalLog.debug(s"finding ${res.size} inter-group dependencies took ${System.currentTimeMillis() - startTime} msec")
        res
      }
      // Increment the counter message by 1 to go from 1/10 to 10/10
      private[ParallelEvaluator] val nextCounterMsg = new Evaluator.NextCounterMsg(sortedGroups.keyCount)
    }

    class EvalLog(startTime: Long) extends FileLogger(false, outPath / "evaluator.log", true, true) {
      override def debug(s: String) =
        super.debug(s"${System.currentTimeMillis() - startTime} [${Thread.currentThread().getName()}] ${s}")
    }

    class TimeLog(startTime: Long) extends FileLogger(false, outPath / "tasks-par.log", true, true) {
      override def debug(s: String) =
        super.debug(s"${System.currentTimeMillis() - startTime} [${Thread.currentThread().getName()}] ${s}")
    }

    def evaluate(): Evaluator.Results = {
      os.makeDir.all(outPath)
      val startTime = System.currentTimeMillis()

      implicit val evalLog: EvalLog = new EvalLog(startTime)
      evalLog.debug(s"Start time: ${new java.util.Date()}")

      implicit val timeLog: TimeLog = new TimeLog(startTime)
      timeLog.debug(s"Evaluate with ${threadCount} threads: ${goals.mkString(" ")}")

      val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

      // TODO: check for interactivity
      // TODO: make sure, multiple goals run in order, e.g. clean compile

      val state = runTasks(sortedGroups)

      val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing[_]]
      for ((k, vs) <- sortedGroups.items()) {
        failing.addAll(
          k,
          vs.items.flatMap(i => Option(state.results.get(i))).collect { case f: Result.Failing[_] => f.map(_._1) }
        )
      }
      Evaluator.writeTimings(state.timings, outPath)

      evalLog.debug(s"End time: ${new java.util.Date()}")

      Evaluator.Results(
        goals.indexed.map(state.results.get(_).map(_._1)),
        state.evaluated,
        transitive,
        failing,
        state.timings,
        state.results.asScala.map { case (k, v) => (k, v.map(_._1)) }
      )
    }

    def runTasks(tasks: MultiBiMap[Either[Task[_], Labelled[Any]], Task[_]])(implicit evalLog: EvalLog, timeLog: TimeLog): State = {
      // Services
      val executorService = Executors.newFixedThreadPool(threadCount)
      val completionService = new ExecutorCompletionService[FutureResult](executorService)

      implicit val state: State = new State(tasks)

      scheduleWork(completionService, "initial request")
      try {
        // Work queue management
        // wait for finished jobs and schedule more work, if possible
        while (!(failFast && state.someTaskFailed.get()) && state.scheduledFutures.size > 0) {
          evalLog.debug(s"Waiting for next future completion of ${executorService}")
          val compFuture: Future[FutureResult] = completionService.take()

          val compTask: TerminalGroup = state.scheduledFutures(compFuture)
          val compTaskName = printTerm(compTask._1)
          evalLog.debug(s"Completed future: ${compFuture} for task ${compTaskName}")
          state.scheduledFutures -= compFuture
          try {
            val FutureResult(
            finishedWork,
            time,
            Evaluated(newResults, newEvaluated, cached)
            ) = compFuture.get()

            // Check if we failed
            if (!state.someTaskFailed.get() && newResults.exists(task => !task._2.isInstanceOf[Success[_]])) {
              state.someTaskFailed.set(true)
            }

            // Update state
            state.evaluated.appendAll(newEvaluated)
            state.results.putAll(newResults.asJava)
            state.timings.append((finishedWork._1, time, cached))
            state.inProgress = state.inProgress.filterNot(_ == finishedWork)
            state.doneMap += finishedWork

          } catch {
            case NonFatal(e) =>
              evalLog.debug(s"future [${compFuture}] of task [${compTaskName}] failed: ${printException(e)}")
              evalLog.debug(s"Current failed terminal group: ${compTask}")
              evalLog.debug(s"Direct dependencies of current failed terminal group: ${state.interGroupDeps(compTask).map(l => printTerm(l._1))}")
              state.someTaskFailed.set(true)
          }

          if (failFast && state.someTaskFailed.get()) {
            // mark remaining goals as aborted
            goals.foreach { goal =>
              if (!state.results.containsKey(goal)) {
                state.results.put(goal, Result.Aborted)
              }
            }
          } else {
            scheduleWork(completionService, compTaskName.toString())
          }
        } // end of while loop

      } catch {
        case NonFatal(e) =>
          evalLog.debug(s"Exception caught: ${printException(e)}")
          evalLog.debug(s"left futures:\n  ${state.scheduledFutures.mapValues(v => printTerm(v._1)).mkString(",\n  ")}")
      } finally {
        // done, cleanup
        evalLog.debug(s"Shutting down executor service: ${executorService}")
        executorService.shutdownNow()
      }

      return state
    }

    /**
      * Searches for new tasks that have no unresolved dependencies and schedule them to run via the executor service.
      */
    def scheduleWork(
      completionService: ExecutorCompletionService[FutureResult],
      issuer: String
    )(implicit
      state: State,
      evalLog: EvalLog,
      timeLog: TimeLog
    ): Unit = {
      // early exit
      if (state.pending.isEmpty || state.inProgress.size > effectiveThreadCount) return

      val scheduleStart = System.currentTimeMillis()

      // newInProgress: the terminal groups without unresolved dependencies
      // newWork: the terminal groups, with unresolved dependencies (need to wait longer)
      val (newInProgress, newWork) = state.pending.partition { termGroup =>
        val deps = state.interGroupDeps(termGroup)
        deps.isEmpty || deps.forall(d => state.doneMap.contains(d))
      }

      // update state
      state.pending = newWork
      state.inProgress = state.inProgress ++ newInProgress

      evalLog.debug(s"Search for ${newInProgress.size} new dep-free tasks took ${System.currentTimeMillis() - scheduleStart} msec")

      if (newInProgress.isEmpty) {
        evalLog.debug(s"No new tasks to schedule (issuer: ${issuer})")
      } else {
        evalLog.debug(s"Scheduling ${newInProgress.size} new tasks (issuer: ${issuer}): ${newInProgress.map(t => printTerm(t._1)).mkString(", ")}")

        // schedule for parallel execution
        newInProgress.zipWithIndex.foreach {
          case (curWork@(terminal, group), index) =>
            val workerFut: java.util.concurrent.Future[FutureResult] = completionService.submit { () =>
              if (failFast && state.someTaskFailed.get()) {
                // we do not start this tasks but instead return with aborted result
                val newResults = group.map(_ -> Aborted)

                evalLog.debug(s"Skipped evaluation (because of earlier failures): ${printTerm(terminal)}")
                FutureResult(curWork, 0, Evaluated(newResults.toMap, Seq(), false))

              } else {

                val counterMsg = state.nextCounterMsg()

                timeLog.debug(s"START ${printTerm(terminal)}")
                val startTime = System.currentTimeMillis()

                val res = evaluateGroupCached(
                  terminal,
                  group,
                  state.results.asScala,
                  counterMsg,
                  reporter,
                  testReporter,
                  logger
                )

                val endTime = System.currentTimeMillis()
                timeLog.debug(s"END   ${printTerm(terminal)} (${
                  if (res.newResults.exists(task => !task._2.isInstanceOf[Success[_]])) "failed, " else ""
                }${
                  if (res.cached) "cached, " else ""
                }${
                  (endTime - startTime).toInt
                })")

                FutureResult(curWork, (endTime - startTime).toInt, res)
              }
            }

            evalLog.debug(s"New future: ${workerFut} [${index + 1}/${newInProgress.size}] for task: ${printTerm(terminal)}")
            state.scheduledFutures += workerFut -> curWork
        }
      }
    }

    def printTerm(term: Terminal): String = term match {
      case Left(t) => t.toString()
      case Right(l) => l.task.toString()
    }

    def printException(e: Throwable): String = {
      val baos = new ByteArrayOutputStream()
      val os = new PrintStream(baos)
      try {
        e.printStackTrace(os)
        baos.toString()
      } finally {
        os.close()
        baos.close()
      }
    }

    private def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]]): Map[TerminalGroup, Seq[TerminalGroup]] = {
      val groupDeps: Map[(Either[Task[_], Labelled[Any]], Agg[Task[_]]), Seq[Task[_]]] = sortedGroups.items().map {
        case g @ (terminal, group) => {
          val externalDeps = group.toSeq.flatMap(_.inputs).filterNot(d => group.contains(d)).distinct
          g -> externalDeps
        }
      }.toMap

      val interGroupDeps: Map[TerminalGroup, Seq[TerminalGroup]] = groupDeps.map {
        case (group, deps) =>
          val depGroups = sortedGroups.items.toList.filter {
            case (otherTerminal, otherGroup) =>
              otherGroup.toList.exists(d => deps.contains(d))
          }
          group -> depGroups
      }

      interGroupDeps
    }
  }
}


object Evaluator{
  case class Cached(value: ujson.Value,
                    valueHash: Int,
                    inputsHash: Int)
  object Cached{
    implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
  }
  case class State(rootModule: mill.define.BaseModule,
                   classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
                   workerCache: mutable.Map[Segments, (Int, Any)],
                   watched: Seq[(os.Path, Long)])
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new ThreadLocal[mill.eval.Evaluator]

  val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  case class Paths(out: os.Path,
                   dest: os.Path,
                   meta: os.Path,
                   log: os.Path)
  def makeSegmentStrings(segments: Segments) = segments.value.flatMap{
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(values) => values.map(_.toString)
  }
  def resolveDestPaths(workspacePath: os.Path, segments: Segments): Paths = {
    val segmentStrings = makeSegmentStrings(segments)
    val targetPath = workspacePath / segmentStrings
    Paths(targetPath, targetPath / 'dest, targetPath / "meta.json", targetPath / 'log)
  }

  // check if the build itself has changed
  def classLoaderSig = Thread.currentThread().getContextClassLoader match {
    case scl: SpecialClassLoader => scl.classpathSignature
    case ucl: URLClassLoader =>
      SpecialClassLoader.initialClasspathSignature(ucl)
    case _ => Nil
  }

  case class Timing(label: String,
                    millis: Int,
                    cached: Boolean)

  object Timing{
    implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
  }

  def writeTimings(timings: Seq[(Either[Task[_], Labelled[_]], Int, Boolean)], outPath: os.Path): Unit = {
    os.write.over(
      outPath / "mill-profile.json",
      upickle.default.stream(
        timings.map { case (k, v, b) =>
          Evaluator.Timing(k.fold(_ => null, s => s.segments.render), v, b)
        },
        indent = 4
      )
    )
  }

  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: Agg[Task[_]],
                     transitive: Agg[Task[_]],
                     failing: MultiBiMap[Either[Task[_], Labelled[_]], Result.Failing[_]],
                     timings: IndexedSeq[(Either[Task[_], Labelled[_]], Int, Boolean)],
                     results: collection.Map[Task[_], Result[Any]]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }

  def plan(rootModule: BaseModule, goals: Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: NamedTask[Any]   =>
        val segments = t.ctx.segments
        val finalTaskOverrides = t match{
          case t: Target[_] =>
            rootModule.millInternal.segmentsToTargets.get(segments).fold(0)(_.ctx.overrides)

          case c: mill.define.Command[_] =>
            def findMatching(cls: Class[_]): Option[Seq[(Int, EntryPoint[_])]] = {
              rootModule.millDiscover.value.get(cls) match{
                case Some(v) => Some(v)
                case None =>
                  cls.getSuperclass match{
                    case null => None
                    case superCls => findMatching(superCls)
                  }
              }
            }

            findMatching(c.cls) match{
              case Some(v) =>
                v.find(_._2.name == c.ctx.segment.pathSegments.head).get._1
              // For now we don't properly support overrides for external modules
              // that do not appear in the Evaluator's main Discovered listing
              case None => 0
            }

          case c: mill.define.Worker[_] => 0
        }

        val additional =
          if (finalTaskOverrides == t.ctx.overrides) Nil
          else Seq(Segment.Label("overriden")) ++ t.ctx.enclosing.split("\\.|#| ").map(Segment.Label)

        Right(Labelled(t, segments ++ additional))
      case t if goals.contains(t) => Left(t)
    }
    (sortedGroups, transitive)
  }

  case class Evaluated(newResults: collection.Map[Task[_], Result[(Any, Int)]], newEvaluated: Seq[Task[_]], cached: Boolean)

  // Increment the counter message by 1 to go from 1/10 to 10/10
  class NextCounterMsg(taskCount: Int) {
    var counter: Int = 0

    def apply(): String = {
      counter += 1
      counter + "/" + taskCount
    }
  }

}
