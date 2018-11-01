package mill.eval

import java.net.URLClassLoader
import java.util.concurrent.{ExecutorCompletionService, Executors}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionException
import scala.util.control.NonFatal

import mill.util.Router.EntryPoint
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

case class Evaluator(home: os.Path,
                     outPath: os.Path,
                     externalOutPath: os.Path,
                     rootModule: mill.define.BaseModule,
                     log: Logger,
                     classLoaderSig: Seq[(Either[String, os.Path], Long)] = Evaluator.classLoaderSig,
                     workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty,
                     env : Map[String, String] = Evaluator.defaultEnv){

  log.debug("Created Evaluator")

  val classLoaderSignHash = classLoaderSig.hashCode()
  def evaluate(goals: Agg[Task[_]]): Evaluator.Results = {
    os.makeDir.all(outPath)

    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    // Mutable collector for all evaluated tasks
    val evaluated = new Agg.Mutable[Task[_]]

    // Mutable collector for all task results
    val results = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    // Mutable collector for timings
    val timings = mutable.ArrayBuffer.empty[(Either[Task[_], Labelled[_]], Int, Boolean)]

    // Increment the counter message by 1 to go from 1/10 to 10/10
    object NextCounterMsg {
      val taskCount = sortedGroups.keyCount
      var counter: Int = 0
      def apply(): String = {
        counter += 1
        counter + "/" + taskCount
      }
    }

    val runParallel = true
    if (runParallel) {

      // TODO: check for interactivity
      // TODO: make sure, multiple goals run in order, e.g. clean compile

      val executorService = Executors.newFixedThreadPool(4)
      log.debug(s"Created executor: ${executorService}")
      val completionService = new ExecutorCompletionService[(TerminalGroup, Evaluated)](executorService)

      val interGroupDeps: Map[TerminalGroup, Seq[TerminalGroup]] = findInterGroupDeps(sortedGroups)

      // State holders, only written to from same thread
      // The unprocessed terminal groups
      var work = sortedGroups.items().toList
      // The currently scheduled (maybe not started yet) terminal groups
      var inProgress = List[TerminalGroup]()
      // The finished terminal groups
      var done = List[TerminalGroup]()
      // The scheduled and not yet finished futures (Java!)
      var futures = List[java.util.concurrent.Future[(TerminalGroup, Evaluated)]]()

      /**
       * Checks for terminal groups, that have no unresolved dependencies and schedule them to run via the executor service.
       */
      def scheduleWork(): Unit = {
        // early exit
        if (work.isEmpty) return

        //        log.debug(s"scheduleWork state:")
        //        log.debug(s"  work:       ${work.size} -- ${work.map(_._1).mkString(", ")}")
        //        log.debug(s"  inProgress: ${inProgress.size} -- ${inProgress.map(_._1).mkString(", ")}")
        //        log.debug(s"  done:       ${done.size} -- ${done.map(_._1).mkString(", ")}")
        //        log.debug(s"  executor:   ${executorService}")

        // newInProgress: the terminal groups without unresolved dependencies
        val (newInProgress, newWork) = work.partition { termGroup =>
          val deps = interGroupDeps(termGroup)
          deps.isEmpty || deps.forall(d => done.contains(d))
        }

        // update state
        work = newWork
        inProgress = inProgress ++ newInProgress

        // schedule for parallel execution
        newInProgress.foreach {
          case curWork @ (terminal, group) =>
            val workerFut: java.util.concurrent.Future[(TerminalGroup, Evaluated)] =
              completionService.submit { () =>
                log.debug(s"Starting evaluation of terminal group: ${terminal}")
                val startTime = System.currentTimeMillis()

                val res @ Evaluated(newResults, newEvaluated, cached) =
                  evaluateGroupCached(
                    terminal,
                    group,
                    results,
                    NextCounterMsg()
                  )

                val endTime = System.currentTimeMillis()
                log.debug(s"Finished evaluation of terminal group: ${terminal}")
                curWork -> res

              }
            log.debug(s"New future: ${workerFut} for task: ${curWork._1}")
            futures = futures ++ List(workerFut)
        }
        log.debug("Finished scheduleWork")
      }

      scheduleWork()

      while (futures.size > 0) {
        log.debug(s"Waiting for next future completion of ${executorService}")
        val compFuture = completionService.take()
        log.debug(s"Completed future: ${compFuture}")
        futures = futures.filterNot(_ == compFuture)
        try {
          val (
            finishedWork,
            Evaluated(newResults, newEvaluated, cached)) = compFuture.get()

          // Update state
          evaluated.appendAll(newEvaluated)
          newResults.foreach { case (k, v) => results.put(k, v) }
          inProgress = inProgress.filterNot(_ == finishedWork)
          done = done ++ Seq(finishedWork)

          // Try to schedule more tasks
          scheduleWork()

        } catch {
          case e: ExecutionException =>
            log.debug(s"task future failed: ${e.getCause()}")
            // stop pending jobs
            futures.foreach(_.cancel(false))
            // break while-loop
            futures = List()
        }
      }

      // done, cleanup
      log.debug(s"Shuting down executor service: ${executorService}")
      executorService.shutdown()

    } else {
      sortedGroups.items().foreach {
        case (terminal, group) => {
          //          log.debug(s"Terminal: ${terminal}\nGroup: ${group.mkString(", ")}")
          val startTime = System.currentTimeMillis()
          val Evaluated(newResults, newEvaluated, cached) =
            evaluateGroupCached(
              terminal,
              group,
              results,
              NextCounterMsg()
            )

          evaluated.appendAll(newEvaluated)
          newResults.foreach { case (k, v) => results.put(k, v) }
          val endTime = System.currentTimeMillis()

          timings.append((terminal, (endTime - startTime).toInt, cached))
        }
      }
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

  type TerminalGroup = (Either[Task[_], Labelled[Any]], Strict.Agg[Task[_]])

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

  case class Evaluated(newResults: collection.Map[Task[_], Result[(Any, Int)]], newEvaluated: Seq[Task[_]], cached: Boolean)

  protected def evaluateGroupCached(
    terminal: Either[Task[_], Labelled[_]],
    group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    counterMsg: String
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

  protected def evaluateGroup(
    group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    inputsHash: Int,
    paths: Option[Evaluator.Paths],
    maybeTargetLabel: Option[String],
    counterMsg: String
  ) = {

    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if (logRun) {
        log.ticker(s"[$counterMsg] $targetLabel ")
      }
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
    case Some(path) => MultiLogger(log.colored, log, FileLogger(log.colored, path, debugEnabled = true))
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
}
