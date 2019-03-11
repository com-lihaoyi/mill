package mill.eval

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.util.concurrent.{ExecutorCompletionService, Executors, Future}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionException
import scala.util.control.NonFatal

import ammonite.runtime.SpecialClassLoader
import mill.define.{Ctx => _, _}
import mill.eval.Result.OuterStack
import mill.util
import mill.util.Router.EntryPoint
import mill.util.Strict.Agg
import mill.util.{Strict, _}

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
  classLoaderSig: Seq[(Either[String, os.Path], Long)] = Evaluator.classLoaderSig,
  workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty,
  env: Map[String, String] = Evaluator.defaultEnv,
  threadCount: Option[Int] = None
) {

  import Evaluator.Evaluated

  val classLoaderSignHash = classLoaderSig.hashCode()

  def evaluate(goals: Agg[Task[_]]): Evaluator.Results =
    if(threadCount.getOrElse(1) > 1) {
      evaluateParallel(goals, threadCount)
    } else {

    os.makeDir.all(outPath)

   val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val timings = mutable.ArrayBuffer.empty[(Either[Task[_], Labelled[_]], Int, Boolean)]
    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex){
      val startTime = System.currentTimeMillis()
      // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
      val counterMsg = (i+1) + "/" + sortedGroups.keyCount
      val Evaluated(newResults, newEvaluated, cached) = evaluateGroupCached(
        terminal,
        group,
        results,
        counterMsg
      )
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
    os.write.over(
      outPath / "mill-profile.json",
      upickle.default.write(
        timings .map{case (k, v, b) =>
          Evaluator.Timing(k.fold(_ => null, s => s.segments.render), v, b)
        },
        indent = 4
      )
    )
    Evaluator.Results(
      goals.indexed.map(results(_).map(_._1)),
      evaluated,
      transitive,
      failing,
      timings,
      results.map{case (k, v) => (k, v.map(_._1))}
    )
   }

  protected def evaluateGroupCached(terminal: Terminal,
    group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    counterMsg: String): Evaluated = {

    // those result which are inputs but not contained in this terminal group
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
          counterMsg = counterMsg
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
              counterMsg = counterMsg
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
    import labelledTask.task.ctx
    if (ctx.foreign) {
      val prefix = "foreign-modules"
      // Computing a path in "out" that uniquely reflects the location
      // of the foreign module relatively to the current build.
      val relative = labelledTask.task
        .ctx.millSourcePath
        .relativeTo(rootModule.millSourcePath)
      // Encoding the number of `/..`
      val ups = if (relative.ups > 0) Segments.labels(s"up-${relative.ups}")
                else Segments()
      Segments.labels(prefix)
        .++(ups)
        .++(Segments.labels(relative.segments: _*))
        .++(labelledTask.segments.last)
    } else labelledTask.segments
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
            upickle.default.write(
              Evaluator.Cached(json, hashCode, inputsHash),
              indent = 4
            )
          )
        }
    }
  }

  protected def evaluateGroup(group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    inputsHash: Int,
    paths: Option[Evaluator.Paths],
    maybeTargetLabel: Option[String],
    counterMsg: String) = PrintLogger.withContext(maybeTargetLabel.map(_ + ": ")) {

    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log.ticker(s"[$counterMsg] $targetLabel ") }
    }

    val multiLogger = resolveLogger(paths.map(_.log))
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
            env
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

    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[os.Path]): Logger = logPath match{
    case None => log
    case Some(path) => MultiLogger(log.colored, log, new FileLogger(log.colored, path, debugEnabled = true))
  }

  type Terminal = Either[Task[_], Labelled[Any]]
  type TerminalGroup = (Terminal, Strict.Agg[Task[_]])

  def evaluateParallel(goals: Agg[Task[_]], threadCount: Option[Int] = None): Evaluator.Results = {
    os.makeDir.all(outPath)

    val startTime = System.currentTimeMillis()

    // we need to collect all relevant logging info while developing
    val evalLog = MultiLogger(true, this.log,
      new FileLogger(false, outPath / "evaluator.log", true, append = true) {
        override def debug(s: String) = super.debug(s"${System.currentTimeMillis() - startTime} [${Thread.currentThread().getName()}] ${s}")
      })

    // As long as this feature is experimental, we default to 1,
    // later we may want to default to nr of processors.
    val threadCount = this.threadCount.
      // getOrElse(Runtime.getRuntime().availableProcessors())
      getOrElse(1)

    evalLog.info(s"Using experimental parallel evaluator with ${threadCount} threads")

    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    // Mutable collector for all evaluated tasks
    val evaluated = new Agg.Mutable[Task[_]]

    // Mutable collector for all task results
    @volatile var results = Map.empty[Task[_], Result[(Any, Int)]]

    type Timing = (Either[Task[_], Labelled[_]], Int, Boolean)

    case class FutureResult(task: TerminalGroup, time: Int, result: Evaluated)

    // Mutable collector for timings
    val timings = mutable.ArrayBuffer.empty[Timing]

    // Increment the counter message by 1 to go from 1/10 to 10/10
    object NextCounterMsg {
      val taskCount = sortedGroups.keyCount
      var counter: Int = 0

      def apply(): String = {
        counter += 1
        counter + "/" + taskCount
      }
    }

    // TODO: check for interactivity
    // TODO: make sure, multiple goals run in order, e.g. clean compile

    val executorService = Executors.newFixedThreadPool(threadCount)
    evalLog.debug(s"Created executor: ${executorService}")
    val completionService = new ExecutorCompletionService[FutureResult](executorService)

    // The scheduled and not yet finished futures (Java!)
    var futures = List[(java.util.concurrent.Future[FutureResult], TerminalGroup)]()

    try {

      val interGroupDeps: Map[TerminalGroup, Seq[TerminalGroup]] = findInterGroupDeps(sortedGroups)
      evalLog.debug(s"${interGroupDeps}")

      // State holders, only written to from same thread
      // The unprocessed terminal groups
      var work = sortedGroups.items().toList
      // The currently scheduled (maybe not started yet) terminal groups
      var inProgress = List[TerminalGroup]()
      // The finished terminal groups
      var done = List[TerminalGroup]()

      if (work.size != work.distinct.size) {
        evalLog.error(s"Work list contains ${work.distinct.size - work.size} duplicates!")
      }

      evalLog.debug(s"Work: ${work.size} -- ${work.map(t => printTerm(t._1)).mkString(", ")}")

      /**
        * Checks for terminal groups, that have no unresolved dependencies and schedule them to run via the executor service.
        */
      def scheduleWork(issuer: String): Unit = {
        // early exit
        if (work.isEmpty) return

        // newInProgress: the terminal groups without unresolved dependencies
        // newWork: the terminal groups, with unresolved dependencies (need to wait longer)
        val (newInProgress, newWork) = work.partition { termGroup =>
          val deps = interGroupDeps(termGroup)
          deps.isEmpty || deps.forall(d => done.contains(d))
        }

        // update state
        work = newWork
        inProgress = inProgress ++ newInProgress

        if (!newInProgress.isEmpty) {
          evalLog.debug(s"Scheduling ${newInProgress.size} new tasks (issuer: ${issuer}): ${newInProgress.map(t => printTerm(t._1)).mkString(", ")}")

          // schedule for parallel execution
          newInProgress.zipWithIndex.foreach {
            case (curWork @ (terminal, group), index) =>

              val missingDependencies = group.indexed.flatMap(_.inputs).filter(t => !results.contains(t) && !group.contains(t))
              if (!missingDependencies.isEmpty) {
                evalLog.error(s"Missing resolved dependencies for terminal group: ${printTerm(terminal)}\n  ${missingDependencies.mkString(",\n  ")}")
              }

              val workerFut: java.util.concurrent.Future[FutureResult] = completionService.submit { () =>
                evalLog.debug(s"Start evaluation: ${printTerm(terminal)}")
                val startTime = System.currentTimeMillis()

                val res @ Evaluated(newResults, newEvaluated, cached) =
                  evaluateGroupCached(
                    terminal,
                    group,
                    results,
                    NextCounterMsg()
                  )

                val endTime = System.currentTimeMillis()
                evalLog.debug(s"Finished evaluation: ${printTerm(terminal)}")

                FutureResult(curWork, (endTime - startTime).toInt, res)
              }

              evalLog.debug(s"New future: ${workerFut} [${index + 1}/${newInProgress.size}] for task: ${printTerm(terminal)}")
              futures = futures ++ List(workerFut -> curWork)
          }
        } else {
          evalLog.debug(s"No new tasks to schedule (issuer: ${issuer})")
        }
      }

      scheduleWork("initial request")

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
          Evaluated(newResults, newEvaluated, cached)) = compFuture.get()

          // Update state
          evaluated.appendAll(newEvaluated)
          results ++= newResults
          timings.append((finishedWork._1, time, cached))

          inProgress = inProgress.filterNot(_ == finishedWork)
          done = done ++ Seq(finishedWork)

          // Try to schedule more tasks
          scheduleWork(compTaskName.toString())
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
    os.write.over(
      outPath / "mill-profile.json",
      upickle.default.write(
        timings .map{case (k, v, b) =>
          Evaluator.Timing(k.fold(_ => null, s => s.segments.render), v, b)
        },
        indent = 4
      )
    )
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

  private def findInterGroupDeps(sortedGroups: MultiBiMap[Either[Task[_], Labelled[Any]], Task[_]]): Map[TerminalGroup, Seq[TerminalGroup]] = {
    val groupDeps: Map[(Either[Task[_], Labelled[Any]], Strict.Agg[Task[_]]), Seq[Task[_]]] = sortedGroups.items().map {
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
                   classLoaderSig: Seq[(Either[String, os.Path], Long)],
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

}
