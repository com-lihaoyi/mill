package mill.eval

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.util.DynamicVariable
import mill.api.{
  CompileProblemReporter,
  Ctx,
  DummyTestReporter,
  Loose,
  PathRef,
  Result,
  Strict,
  TestReporter,
  Val
}
import mill.api.Result.{Aborted, Failing, OuterStack, Success}
import mill.api.Strict.Agg
import mill.define._
import mill.eval.Evaluator.{TaskResult, Terminal}
import mill.util._

import scala.collection.mutable
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
 * Evaluate tasks.
 */
private[mill] case class EvaluatorImpl(
    home: os.Path,
    outPath: os.Path,
    externalOutPath: os.Path,
    rootModule: mill.define.BaseModule,
    baseLogger: ColorLogger,
    classLoaderSigHash: Int,
    workerCache: mutable.Map[Segments, (Int, Val)] = mutable.Map.empty,
    env: Map[String, String] = Evaluator.defaultEnv,
    failFast: Boolean = true,
    threadCount: Option[Int] = Some(1),
    scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])] = Map.empty
) extends Evaluator {
  import EvaluatorImpl._

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  override def withBaseLogger(newBaseLogger: ColorLogger): Evaluator =
    this.copy(baseLogger = newBaseLogger)

  override def withFailFast(newFailFast: Boolean): Evaluator =
    this.copy(failFast = newFailFast)

  val pathsResolver: EvaluatorPathsResolver = EvaluatorPathsResolver.default(outPath)

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

  def getFailing(
      sortedGroups: MultiBiMap[Terminal, Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]]
  ): MultiBiMap.Mutable[Terminal, Failing[Val]] = {
    val failing = new MultiBiMap.Mutable[Terminal, Result.Failing[Val]]
    for ((k, vs) <- sortedGroups.items()) {
      val failures = vs.items.flatMap(results.get).collect {
        case TaskResult(f: Result.Failing[(Val, Int)], _) => f.map(_._1)
      }

      failing.addAll(k, Loose.Agg.from(failures))
    }
    failing
  }

  def evaluate0(
      goals: Agg[Task[_]],
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      ec: ExecutionContext with AutoCloseable,
      contextLoggerMsg: Int => String
  ): Evaluator.Results = {
    implicit val implicitEc = ec

    os.makeDir.all(outPath)
    val timeLog = new ParallelProfileLogger(outPath, System.currentTimeMillis())
    val timings = mutable.ArrayBuffer.empty[(Terminal, Int, Boolean)]
    val (sortedGroups, transitive) = EvaluatorImpl.plan(goals)
    val interGroupDeps = findInterGroupDeps(sortedGroups)
    val terminals = sortedGroups.keys().toVector
    val failed = new AtomicBoolean(false)
    val count = new AtomicInteger(1)
    val futures = mutable.Map.empty[Terminal, Future[Option[TaskGroupResults]]]

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

          val startTime = System.currentTimeMillis()
          val threadId = timeLog.getThreadId(Thread.currentThread().getName())
          val counterMsg = s"${count.getAndIncrement()}/${terminals.size}"
          val contextLogger = PrefixLogger(
            out = logger,
            context = contextLoggerMsg(threadId),
            tickerContext = EvaluatorImpl.dynamicTickerPrefix.value
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

          val endTime = System.currentTimeMillis()
          timeLog.timeTrace(
            task = printTerm(terminal),
            cat = "job",
            startTime = startTime,
            endTime = endTime,
            thread = Thread.currentThread().getName(),
            cached = res.cached
          )
          timings.append((terminal, (endTime - startTime).toInt, res.cached))
          Some(res)
        }
      }
    }

    EvaluatorImpl.writeTimings(timings.toSeq, outPath)

    val finishedOptsMap = terminals
      .map(t => (t, Await.result(futures(t), duration.Duration.Inf)))
      .toMap

    val results0: Vector[(Task[_], TaskResult[(Val, Int)])] = terminals
      .flatMap { t =>
        sortedGroups.lookupKey(t).flatMap { t0 =>
          finishedOptsMap(t) match {
            case None => Some((t0, TaskResult(Aborted, () => Aborted)))
            case Some(res) => res.newResults.get(t0).map(r => (t0, r))
          }
        }
      }

    val results: Map[Task[_], TaskResult[(Val, Int)]] = results0.toMap

    timeLog.close()

    EvaluatorImpl.Results(
      goals.indexed.map(results(_).map(_._1).result),
      finishedOptsMap.map(_._2).flatMap(_.toSeq.flatMap(_.newEvaluated)),
      transitive,
      getFailing(sortedGroups, results),
      results.map { case (k, v) => (k, v.map(_._1)) }
    )
  }

  // those result which are inputs but not contained in this terminal group
  def evaluateGroupCached(
      terminal: Terminal,
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      counterMsg: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger
  ): TaskGroupResults = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).result.asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.iterator.map(_.sideHash)
    )

    val scriptsHash = {
      val possibleScripts = scriptImportGraph.keySet.map(_.toString)
      val scripts = new Loose.Agg.Mutable[os.Path]()
      group.iterator.flatMap(t => Iterator(t) ++ t.inputs).foreach {
        // Filter out the `fileName` as a string before we call `os.Path` on it, because
        // otherwise linux paths on earlier-compiled artifacts can cause this to crash
        // when running on Windows with a different file path format
        case namedTask: NamedTask[_] if possibleScripts.contains(namedTask.ctx.fileName) =>
          scripts.append(os.Path(namedTask.ctx.fileName))
        case _ =>
      }

      val transitiveScripts = Graph.transitiveNodes(scripts)(t =>
        scriptImportGraph.get(t).map(_._2).getOrElse(Nil)
      )

      transitiveScripts
        .iterator
        // Sometimes tasks are defined in external/upstreadm dependencies,
        // (e.g. a lot of tasks come from JavaModule.scala) and won't be
        // present in the scriptImportGraph
        .map(p => scriptImportGraph.get(p).fold(0)(_._1))
        .sum
    }

    val inputsHash = externalInputsHash + sideHashes + classLoaderSigHash + scriptsHash

    terminal match {
      case Terminal.Task(task) =>
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
        TaskGroupResults(newResults, newEvaluated.toSeq, false)

      case labelled: Terminal.Labelled[_] =>
        val out =
          if (!labelled.task.ctx.external) outPath
          else externalOutPath

        val paths = EvaluatorPaths.resolveDestPaths(
          out,
          destSegments(labelled)
        )

        val cached = loadCachedJson(logger, inputsHash, labelled, paths)

        val upToDateWorker = loadUpToDateWorker(logger, inputsHash, labelled)

        upToDateWorker.map((_, inputsHash)) orElse cached match {
          case Some((v, hashCode)) =>
            val res = Result.Success((v, hashCode))
            val newResults: Map[Task[_], TaskResult[(Val, Int)]] =
              Map(labelled.task -> TaskResult(res, () => res))

            TaskGroupResults(newResults, Nil, cached = true)

          case _ =>
            // uncached
            if (labelled.task.flushDest) os.remove.all(paths.dest)

            val targetLabel = printTerm(terminal)

            val (newResults, newEvaluated) =
              EvaluatorImpl.dynamicTickerPrefix.withValue(s"[$counterMsg] $targetLabel > ") {
                evaluateGroup(
                  group,
                  results,
                  inputsHash,
                  paths = Some(paths),
                  maybeTargetLabel = Some(targetLabel),
                  counterMsg = counterMsg,
                  zincProblemReporter,
                  testReporter,
                  logger
                )
              }

            newResults(labelled.task) match {
              case TaskResult(Result.Failure(_, Some((v, _))), _) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelled)

              case TaskResult(Result.Success((v, _)), _) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelled)

              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                os.remove.all(paths.meta)
            }

            TaskGroupResults(newResults, newEvaluated.toSeq, cached = false)
        }
    }
  }

  def loadUpToDateWorker(logger: ColorLogger, inputsHash: Int, labelled: Terminal.Labelled[_]): Option[Val] = {
    labelled.task.asWorker
      .flatMap { w => workerCache.synchronized {workerCache.get(w.ctx.segments)}}
      .flatMap {
        case (`inputsHash`, upToDate) => Some(upToDate) // worker cached and up-to-date
        case (_, Val(obsolete: AutoCloseable)) =>
          // worker cached but obsolete, needs to be closed
          try {
            logger.debug(s"Closing previous worker: ${labelled.segments.render}")
            obsolete.close()
          } catch {
            case NonFatal(e) =>
              logger.error(
                s"${labelled.segments.render}: Errors while closing obsolete worker: ${e.getMessage()}"
              )
          }
          // make sure, we can no longer re-use a closed worker
          labelled.task.asWorker.foreach { w =>
            workerCache.synchronized {
              workerCache.remove(w.ctx.segments)
            }
          }
          None

        case _ => None // worker not cached or obsolete
      }
  }

  def loadCachedJson(logger: ColorLogger, inputsHash: Int, labelled: Terminal.Labelled[_], paths: EvaluatorPaths): Option[(Val, Int)] = {
    for {
      cached <-
        try Some(upickle.default.read[Evaluator.Cached](paths.meta.toIO))
        catch {
          case NonFatal(_) => None
        }
      if cached.inputsHash == inputsHash
      reader <- labelled.task.readWriterOpt
      parsed <-
        try Some(upickle.default.read(cached.value)(reader))
        catch {
          case e: PathRef.PathRefValidationException =>
            logger.debug(
              s"${labelled.segments.render}: re-evaluating; ${e.getMessage}"
            )
            None
          case NonFatal(_) => None
        }
    } yield (Val(parsed), cached.valueHash)
  }

  def destSegments(labelledTask: Terminal.Labelled[_]): Segments = {
    labelledTask.task.ctx.foreign match {
      case Some(foreignSegments) => foreignSegments ++ labelledTask.segments
      case None => labelledTask.segments
    }
  }

  def handleTaskResult(
      v: Val,
      hashCode: Int,
      metaPath: os.Path,
      inputsHash: Int,
      labelled: Terminal.Labelled[_]
  ): Unit = {
    labelled.task.asWorker match {
      case Some(w) =>
        workerCache.synchronized {
          workerCache.update(w.ctx.segments, (inputsHash, v))
        }
      case None =>
        val terminalResult = labelled
          .task
          .writerOpt
          .asInstanceOf[Option[upickle.default.Writer[Any]]]
          .map { w => upickle.default.writeJs(v.value)(w) }

        for (json <- terminalResult) {
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

  def evaluateGroup(
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      inputsHash: Int,
      paths: Option[EvaluatorPaths],
      maybeTargetLabel: Option[String],
      counterMsg: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger
  ): (Map[Task[_], TaskResult[(Val, Int)]], mutable.Buffer[Task[_]]) = {

    def computeAll(enableTicker: Boolean) = {
      val newEvaluated = mutable.Buffer.empty[Task[_]]
      val newResults = mutable.Map.empty[Task[_], Result[(Val, Int)]]

      val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

      // should we log progress?
      val logRun = maybeTargetLabel.isDefined && {
        val inputResults = for {
          target <- nonEvaluatedTargets
          item <- target.inputs.filterNot(group.contains)
        } yield results(item).map(_._1)
        inputResults.forall(_.result.isInstanceOf[Result.Success[_]])
      }

      val tickerPrefix = maybeTargetLabel.map { targetLabel =>
        val prefix = s"[$counterMsg] $targetLabel "
        if (logRun && enableTicker) logger.ticker(prefix)
        prefix + "| "
      }

      val multiLogger = new ProxyLogger(resolveLogger(paths.map(_.log), logger)) {
        override def ticker(s: String): Unit = {
          if (enableTicker) super.ticker(tickerPrefix.getOrElse("") + s)
          else () // do nothing
        }
      }
      // This is used to track the usage of `T.dest` in more than one Task
      // But it's not really clear what issue we try to prevent here
      // Vice versa, being able to use T.dest in multiple `T.task`
      // is rather essential to split up larger tasks into small parts
      // So I like to disable this detection for now
      var usedDest = Option.empty[(Task[_], Array[StackTraceElement])]
      for (task <- nonEvaluatedTargets) {
        newEvaluated.append(task)
        val targetInputValues = task.inputs
          .map { x => newResults.getOrElse(x, results(x).result) }
          .collect { case Result.Success((v, _)) => v }

        val res = {
          if (targetInputValues.length != task.inputs.length) Result.Skipped
          else {
            val args = new Ctx(
              args = targetInputValues.map(_.value).toIndexedSeq,
              dest0 = () =>
                paths match {
                  case Some(dest) =>
                    if (usedDest.isEmpty) os.makeDir.all(dest.dest)
                    usedDest = Some((task, new Exception().getStackTrace))
                    dest.dest
                  case None =>
                    throw new Exception("No `dest` folder available here")
                  //                }
                },
              log = multiLogger,
              home = home,
              env = env,
              reporter = reporter,
              testReporter = testReporter,
              workspace = rootModule.millSourcePath
            ) with mill.api.Ctx.Jobs {
              override def jobs: Int = effectiveThreadCount
            }

            mill.api.SystemStreams.withStreams(multiLogger.systemStreams) {
              try task.evaluate(args).map(Val(_))
              catch {
                case NonFatal(e) =>
                  Result.Exception(
                    e,
                    new OuterStack(new Exception().getStackTrace.toIndexedSeq)
                  )
              }
            }
          }
        }

        newResults(task) = for (v <- res) yield {
          (
            v,
            if (task.isInstanceOf[Worker[_]]) inputsHash
            else v.##
          )
        }
      }
      multiLogger.close()
      (newResults, newEvaluated)
    }

    val (newResults, newEvaluated) = computeAll(enableTicker = true)

    if (!failFast) maybeTargetLabel.foreach { targetLabel =>
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[_]])
      if (taskFailed) {
        logger.error(s"[${counterMsg}] ${targetLabel} failed")
      }
    }

    (
      newResults
        .map { case (k, v) =>
          val recalc = () => computeAll(enableTicker = false)._1.apply(k)
          val taskResult = TaskResult(v, recalc)
          (k, taskResult)
        }
        .toMap,
      newEvaluated
    )
  }

  def resolveLogger(logPath: Option[os.Path], logger: mill.api.Logger): mill.api.Logger =
    logPath match {
      case None => logger
      case Some(path) => new MultiLogger(
          logger.colored,
          logger,
          // we always enable debug here, to get some more context in log files
          new FileLogger(logger.colored, path, debugEnabled = true),
          logger.systemStreams.in,
          debugEnabled = logger.debugEnabled
        )
    }

  // TODO: we could track the deps of the dependency chain, to prioritize tasks with longer chain
  // TODO: we could also track the number of other tasks that depends on a task to prioritize
  def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]])
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

  def printTerm(term: Terminal): String = term match {
    case Terminal.Task(task) => task.toString()
    case labelled: Terminal.Labelled[_] =>
      val Seq(first, rest @ _*) = destSegments(labelled).value
      val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(s) => "[" + s.mkString(",") + "]"
      }
      msgParts.mkString
  }

  override def plan(goals: Agg[Task[_]])
      : (MultiBiMap[Evaluator.Terminal, Task[_]], Agg[Task[_]]) = {
    EvaluatorImpl.plan(goals)
  }

  override def evalOrThrow(exceptionFactory: Evaluator.Results => Throwable)
      : Evaluator.EvalOrThrow =
    new EvalOrThrow(this, exceptionFactory)
}

private[mill] object EvaluatorImpl {
  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: Evaluator.Results => Throwable)
      extends Evaluator.EvalOrThrow {
    def apply[T: ClassTag](task: Task[T]): T =
      evaluator.evaluate(Agg(task)) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r =>
          // Input is a single-item Agg, so we also expect a single-item result
          val Seq(Val(e: T)) = r.values
          e
      }

    def apply[T: ClassTag](tasks: Seq[Task[T]]): Seq[T] =
      evaluator.evaluate(tasks) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r => r.values.map(_.value).asInstanceOf[Seq[T]]
      }
  }

  case class Results(
      rawValues: Seq[Result[Val]],
      evaluated: Agg[Task[_]],
      transitive: Agg[Task[_]],
      failing: MultiBiMap[Terminal, Result.Failing[Val]],
      results: Map[Task[_], TaskResult[Val]]
  ) extends Evaluator.Results

  case class Timing(label: String, millis: Int, cached: Boolean)

  object Timing {
    implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
  }

  def writeTimings(
      timings: Seq[(Terminal, Int, Boolean)],
      outPath: os.Path
  ): Unit = {
    os.write.over(
      outPath / "mill-profile.json",
      upickle.default.stream(
        timings.map { case (k, v, b) =>
          EvaluatorImpl.Timing(k.render, v, b)
        },
        indent = 4
      )
    )
  }

  def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val seen = collection.mutable.Set.empty[Segments]
    val overridden = collection.mutable.Set.empty[Task[_]]
    topoSorted.values.reverse.iterator.foreach {
      case x: NamedTask[_] if x.isPrivate == Some(true) =>
        // we always need to store them in the super-path
        overridden.add(x)
      case x: NamedTask[_] =>
        if (!seen.contains(x.ctx.segments)) seen.add(x.ctx.segments)
        else overridden.add(x)
      case _ => // donothing
    }

    val sortedGroups: MultiBiMap[Terminal, Task[_]] =
      Graph.groupAroundImportantTargets(topoSorted) {
        // important: all named tasks and those explicitly requested
        case t: NamedTask[Any] =>
          val segments = t.ctx.segments
          val augmentedSegments =
            if (!overridden(t)) segments
            else {
              val Segment.Label(tName) = segments.value.last
              Segments(
                segments.value.init ++
                  Seq(Segment.Label(tName + ".super")) ++
                  t.ctx.enclosing.split("[.# ]").map(Segment.Label)
              )
            }
          Terminal.Labelled(t, augmentedSegments)

        case t if goals.contains(t) => Terminal.Task(t)
      }

    (sortedGroups, transitive)
  }

  case class TaskGroupResults(
      newResults: Map[Task[_], TaskResult[(Val, Int)]],
      newEvaluated: Seq[Task[_]],
      cached: Boolean
  )

  val dynamicTickerPrefix = new DynamicVariable("")
}
