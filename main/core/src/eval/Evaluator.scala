package mill.eval

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.util.concurrent.{ExecutorCompletionService, Executors, Future}

import ammonite.runtime.SpecialClassLoader
import mill.api.Result.{Aborted, OuterStack, Success}
import mill.api.Strict.Agg
import mill.api.{DummyTestReporter, TestReporter, BuildProblemReporter}
import mill.define.{Ctx => _, _}
import mill.util
import mill.util.Router.EntryPoint
import mill.util._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionException
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
      evaluateParallel(goals, effectiveThreadCount, reporter, testReporter, logger)
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
          )

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

  def evaluateParallel(
    goals: Agg[Task[_]],
    threadCount: Int,
    reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
    testReporter: TestReporter = DummyTestReporter,
    logger: Logger = log
  ): Evaluator.Results = {
    os.makeDir.all(outPath)

    val startTime = System.currentTimeMillis()

    // we need to collect all relevant logging info while developing
    val evalLog = MultiLogger(true, this.log,
      new FileLogger(false, outPath / "evaluator.log", true, append = true) {
        override def debug(s: String) = super.debug(s"${System.currentTimeMillis() - startTime} [${Thread.currentThread().getName()}] ${s}")
      })

    evalLog.info(s"Using experimental parallel evaluator with ${threadCount} threads")
    evalLog.debug(s"Start time: ${new java.util.Date()}")

    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    // Mutable collector for all evaluated tasks
    val evaluated = new Agg.Mutable[Task[_]]

    // Mutable collector for all task results
    @volatile var results = Map.empty[Task[_], Result[(Any, Int)]]

    type Timing = (Either[Task[_], Labelled[_]], Int, Boolean)

    case class FutureResult(task: TerminalGroup, time: Int, result: Evaluated)

    // Mutable collector for timings
    val timings = new mutable.ArrayBuffer[Timing](sortedGroups.keyCount)

    // Increment the counter message by 1 to go from 1/10 to 10/10
    val nextCounterMsg = new Evaluator.NextCounterMsg(sortedGroups.keyCount)

    // TODO: check for interactivity
    // TODO: make sure, multiple goals run in order, e.g. clean compile

    val executorService = Executors.newFixedThreadPool(threadCount)
    evalLog.debug(s"Created executor: ${executorService}")
    val completionService = new ExecutorCompletionService[FutureResult](executorService)

    // The scheduled and not yet finished futures (Java!)
    var futures = List[(java.util.concurrent.Future[FutureResult], TerminalGroup)]()

    try {

      val interGroupDeps: Map[TerminalGroup, Seq[TerminalGroup]] = findInterGroupDeps(sortedGroups)
      evalLog.debug(s"${interGroupDeps} (took ${System.currentTimeMillis() - startTime} msec)")

      // State holders, only written to from same thread
      // The unprocessed terminal groups
      var work = sortedGroups.items().toList
      // The currently scheduled (maybe not started yet) terminal groups
      var inProgress = List[TerminalGroup]()
      // The finished terminal groups
      var doneMap = Map[TerminalGroup, Boolean]().withDefaultValue(false)
      // The fact that at least one task failed
      @volatile var someTaskFailed: Boolean = false

      if (work.size != work.distinct.size) {
        evalLog.error(s"Work list contains ${work.distinct.size - work.size} duplicates!")
      }

//      evalLog.debug(s"Work: ${work.size} -- ${work.map(t => printTerm(t._1)).mkString(", ")}")

      /**
        * Checks for terminal groups, that have no unresolved dependencies and schedule them to run via the executor service.
        */
      def scheduleWork(issuer: String): Unit = {
        // early exit
        if (work.isEmpty || inProgress.size > effectiveThreadCount) return

        val scheduleStart = System.currentTimeMillis()

        // newInProgress: the terminal groups without unresolved dependencies
        // newWork: the terminal groups, with unresolved dependencies (need to wait longer)
        val (newInProgress, newWork) = work.partition { termGroup =>
          val deps = interGroupDeps(termGroup)
          deps.isEmpty || deps.forall(d => doneMap(d))
        }

        // update state
        work = newWork
        inProgress = inProgress ++ newInProgress

        evalLog.debug(s"Search for ${newInProgress.size} new dep-free tasks took ${System.currentTimeMillis() - scheduleStart} msec")

        if (!newInProgress.isEmpty) {
          evalLog.debug(s"Scheduling ${newInProgress.size} new tasks (issuer: ${issuer}): ${newInProgress.map(t => printTerm(t._1)).mkString(", ")}")

          // schedule for parallel execution
          newInProgress.zipWithIndex.foreach {
            case (curWork @ (terminal, group), index) =>

              //              val missingDependencies = group.indexed.flatMap(_.inputs).filter(t => !results.contains(t) && !group.contains(t))
              //              if (!missingDependencies.isEmpty) {
              //                evalLog.error(s"Missing resolved dependencies for terminal group: ${printTerm(terminal)}\n  ${missingDependencies.mkString(",\n  ")}")
              //              }

              val workerFut: java.util.concurrent.Future[FutureResult] = completionService.submit { () =>
                if(failFast && someTaskFailed) {
                  // we do not start this tasks but instead return with aborted result
                  val newResults = group.map { task =>
                    task -> Aborted
                  }

                  evalLog.debug(s"Skipped evaluation (because of earlier failures): ${printTerm(terminal)}")
                  FutureResult(curWork, 0, Evaluated(newResults.toMap, Seq(), false))

                } else {

                  val counterMsg = nextCounterMsg()

                  evalLog.debug(s"Start evaluation [${counterMsg}]: ${printTerm(terminal)}")
                  val startTime = System.currentTimeMillis()

                  val res = evaluateGroupCached(
                    terminal,
                    group,
                    results,
                    counterMsg,
                    reporter,
                    testReporter,
                    logger
                  )

                  val endTime = System.currentTimeMillis()
                  evalLog.debug(s"Finished evaluation [${counterMsg}]: ${printTerm(terminal)}")

                  FutureResult(curWork, (endTime - startTime).toInt, res)
                }
              }

              evalLog.debug(s"New future: ${workerFut} [${index + 1}/${newInProgress.size}] for task: ${printTerm(terminal)}")
              futures = futures ++ List(workerFut -> curWork)
          }
        } else {
          evalLog.debug(s"No new tasks to schedule (issuer: ${issuer})")
        }
      }

      scheduleWork("initial request")

      // Work queue management
      // wait for finished jobs and schedule more work, if possible
      while (futures.size > 0) {
        evalLog.debug(s"Waiting for next future completion of ${executorService}")
        val compFuture: Future[FutureResult] = completionService.take()

        val compTask = futures.find(_._1 == compFuture).map(_._2).get
        val compTaskName = printTerm(compTask._1)
        evalLog.debug(s"Completed future: ${compFuture} for task ${compTaskName}")
        futures = futures.filterNot(_._1 == compFuture)
        try {
          val FutureResult(
            finishedWork,
            time,
            Evaluated(newResults, newEvaluated, cached)
          ) = compFuture.get()

          // Check if we failed
          someTaskFailed = someTaskFailed || newResults.exists(task => !task._2.isInstanceOf[Success[_]])

          // Update state
          evaluated.appendAll(newEvaluated)
          results ++= newResults
          timings.append((finishedWork._1, time, cached))
          inProgress = inProgress.filterNot(_ == finishedWork)
          doneMap += finishedWork -> true

          if (failFast && someTaskFailed) {
            // we exit early and set aborted state for all left tasks
            //          group.foreach { task =>
            //            results.put(task, aborted)
            //          }
            //            executorService.shutdownNow()
            // set result state for not run jobs
            goals.foreach { goal =>
              results.get(goal) match {
                case None => results += goal -> Result.Aborted
                case _ => // nothing to do
              }
            }

            executorService.shutdownNow()


          } else {
            // Try to schedule more tasks
            scheduleWork(compTaskName.toString())
          }

        } catch {
          case e: ExecutionException =>
            evalLog.error(s"future [${compFuture}] of task [${compTaskName}] failed: ${printException(e)}")
            evalLog.debug(s"Current failed terminal group: ${compTask}")
            evalLog.debug(s"Direct dependencies of current failed terminal group: ${interGroupDeps(compTask).map(l => printTerm(l._1))}")
            throw e
        }
      }

    } catch {
      case NonFatal(e) =>
        evalLog.error(s"Execption caught: ${printException(e)}")
        evalLog.debug(s"left futures:\n  ${futures.map(f => f._1 -> printTerm(f._2._1)).mkString(",\n  ")}")
        // stop pending jobs
        futures.foreach(_._1.cancel(false))
        // break while-loop
        throw e

    } finally {

      // done, cleanup
      evalLog.debug(s"Shutting down executor service: ${executorService}")
      executorService.shutdownNow()
    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing[_]]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(
        k,
        vs.items.flatMap(results.get).collect{case f: Result.Failing[_] => f.map(_._1)}
      )
    }
    Evaluator.writeTimings(timings, outPath)

    evalLog.debug(s"End time: ${new java.util.Date()}")

    Evaluator.Results(
      goals.indexed.map(results(_).map(_._1)),
      evaluated,
      transitive,
      failing,
      timings,
      results.map{case (k, v) => (k, v.map(_._1))}
    )
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
