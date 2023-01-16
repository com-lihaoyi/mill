package mill.eval

import java.net.{URL, URLClassLoader}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import ammonite.runtime.SpecialClassLoader

import scala.util.DynamicVariable
import mill.api.{CompileProblemReporter, Ctx, DummyTestReporter, Loose, Strict, TestReporter}
import mill.api.Result.{Aborted, Failing, OuterStack, Success}
import mill.api.Strict.Agg
import mill.define._
import mill.internal.AmmoniteUtils
import mill.util._
import upickle.default

import scala.annotation.nowarn
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

case class Labelled[T](task: NamedTask[T], segments: Segments) {
  def format: Option[default.ReadWriter[T]] = task match {
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer: Option[default.Writer[T]] = task match {
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: mill.define.Input[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}

/**
 * Evaluate tasks.
 */
class Evaluator private[Evaluator] (
    _home: os.Path,
    _outPath: os.Path,
    _externalOutPath: os.Path,
    _rootModule: mill.define.BaseModule,
    _baseLogger: ColorLogger,
    _classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
    _workerCache: mutable.Map[Segments, (Int, Any)],
    _env: Map[String, String],
    _failFast: Boolean,
    _threadCount: Option[Int],
    _importTree: Seq[ScriptNode]
) {

  import Evaluator.Terminal

  def home: os.Path = _home

  /**
   * The output base path.
   */
  def outPath: os.Path = _outPath

  /**
   * The output base path to use for external modules.
   */
  def externalOutPath: os.Path = _externalOutPath

  /**
   * The projects root module.
   */
  def rootModule: mill.define.BaseModule = _rootModule
  def baseLogger: ColorLogger = _baseLogger
  def classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = _classLoaderSig

  /**
   * Mutable worker cache.
   */
  def workerCache: mutable.Map[Segments, (Int, Any)] = _workerCache
  def env: Map[String, String] = _env

  /**
   * If `true` the first failing task will fail the evaluation.
   * If `false`, it tries to evaluate all tasks, running longer and reporting possibly more than one failure.
   */
  def failFast: Boolean = _failFast

  /**
   * If a [[Some]] the explicit number of threads to use for parallel task evaluation,
   * or [[None]] to use n threads where n is the number of available logical processors.
   */
  def threadCount: Option[Int] = _threadCount

  /**
   * The tree of imports of the build ammonite scripts
   */
  def importTree: Seq[ScriptNode] = _importTree

  private val (scriptsClassLoader, externalClassLoader) = classLoaderSig.partitionMap {
    case (Right(elem), sig) => Right((elem, sig))
    case (Left(elem), sig) => Left((elem, sig))
  }

  // We're interested of the whole file hash.
  // So we sum the hash of all classes that normalize to the same name.
  private val scriptsSigMap: Map[String, Long] =
    scriptsClassLoader.groupMapReduce(e =>
      AmmoniteUtils.normalizeAmmoniteImportPath(e._1)
    )(_._2)(_ + _)

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  import Evaluator.Evaluated

  private val externalClassLoaderSigHash = externalClassLoader.hashCode()

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

    if (effectiveThreadCount > 1)
      parallelEvaluate(goals, effectiveThreadCount, logger, reporter, testReporter)
    else sequentialEvaluate(goals, logger, reporter, testReporter)
  }

  def sequentialEvaluate(
      goals: Agg[Task[_]],
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter
  ): Evaluator.Results = {
    val (sortedGroups, transitive) = Evaluator.plan(goals)
    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], mill.api.Result[(Any, Int)]]
    var someTaskFailed: Boolean = false

    val timings = mutable.ArrayBuffer.empty[(Either[Task[_], Labelled[_]], Int, Boolean)]
    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex) {
      if (failFast && someTaskFailed) {
        // we exit early and set aborted state for all left tasks
        group.iterator.foreach { task => results.put(task, Aborted) }

      } else {

        val contextLogger = PrefixLogger(
          out = logger,
          context = "",
          tickerContext = Evaluator.dynamicTickerPrefix.value
        )

        val startTime = System.currentTimeMillis()

        // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
        val counterMsg = s"${(i + 1)}/${sortedGroups.keyCount}"

        val Evaluated(newResults, newEvaluated, cached) = evaluateGroupCached(
          terminal = terminal,
          group = group,
          results = results,
          counterMsg = counterMsg,
          zincProblemReporter = reporter,
          testReporter = testReporter,
          logger = contextLogger
        )
        someTaskFailed =
          someTaskFailed || newResults.exists(task => !task._2.isInstanceOf[Success[_]])

        for (ev <- newEvaluated) evaluated.append(ev)
        for ((k, v) <- newResults) results.put(k, v)
        val endTime = System.currentTimeMillis()

        timings.append((terminal, (endTime - startTime).toInt, cached))
      }
    }

    Evaluator.writeTimings(timings.toSeq, outPath)
    Evaluator.Results(
      rawValues = goals.indexed.map(results(_).map(_._1)),
      evaluated = evaluated,
      transitive = transitive,
      failing = getFailing(sortedGroups, results),
      results = results.map { case (k, v) => (k, v.map(_._1)) }
    )
  }

  def getFailing(
      sortedGroups: MultiBiMap[Either[Task[_], Labelled[Any]], Task[_]],
      results: collection.Map[Task[_], mill.api.Result[(Any, Int)]]
  ): MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Failing[_]] = {
    val failing = new MultiBiMap.Mutable[Either[Task[_], Labelled[_]], mill.api.Result.Failing[_]]
    for ((k, vs) <- sortedGroups.items()) {
      failing.addAll(
        k,
        vs.items.flatMap(results.get).collect { case f: mill.api.Result.Failing[_] => f.map(_._1) }
      )
    }
    failing
  }

  def parallelEvaluate(
      goals: Agg[Task[_]],
      threadCount: Int,
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter
  ): Evaluator.Results = {
    os.makeDir.all(outPath)
    val timeLog = new ParallelProfileLogger(outPath, System.currentTimeMillis())

    val (sortedGroups, transitive) = Evaluator.plan(goals)

    val interGroupDeps = findInterGroupDeps(sortedGroups)
    import scala.concurrent._
    val threadPool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
    try {
      implicit val ec: ExecutionContext = new ExecutionContext {
        def execute(runnable: Runnable): Unit = threadPool.submit(runnable)
        def reportFailure(t: Throwable): Unit = {}
      }

      val terminals = sortedGroups.keys().toVector

      val failed = new AtomicBoolean(false)
      val totalCount = terminals.size
      val count = new AtomicInteger(1)
      val futures = mutable.Map.empty[Terminal, Future[Option[Evaluated]]]

      // We walk the task graph in topological order and schedule the futures
      // to run asynchronously. During this walk, we store the scheduled futures
      // in a dictionary. When scheduling each future, we are guaranteed that the
      // necessary upstream futures will have already been scheduled and stored,
      // due to the topological order of traversal.
      for (k <- terminals) {
        val deps = interGroupDeps(k)
        futures(k) = Future.sequence(deps.map(futures)).map { upstreamValues =>
          if (failed.get()) None
          else {
            val upstreamResults = upstreamValues
              .iterator
              .flatMap(_.iterator.flatMap(_.newResults))
              .toMap

            val startTime = System.currentTimeMillis()
            val threadId = timeLog.getThreadId(Thread.currentThread().getName())
            val fraction = s"${count.getAndIncrement()}/$totalCount"
            val contextLogger = PrefixLogger(
              out = logger,
              context = s"[#${if (effectiveThreadCount > 9) f"$threadId%02d" else threadId}] ",
              tickerContext = Evaluator.dynamicTickerPrefix.value
            )

            val res = evaluateGroupCached(
              k,
              sortedGroups.lookupKey(k),
              upstreamResults,
              fraction,
              reporter,
              testReporter,
              contextLogger
            )

            if (failFast && res.newResults.values.exists(_.asSuccess.isEmpty)) failed.set(true)

            val endTime = System.currentTimeMillis()
            timeLog.timeTrace(
              task = printTerm(k),
              cat = "job",
              startTime = startTime,
              endTime = endTime,
              thread = Thread.currentThread().getName(),
              cached = res.cached
            )
            Some(res)
          }
        }
      }

      val finishedOpts = terminals
        .map(t => (t, Await.result(futures(t), duration.Duration.Inf)))

      val finishedOptsMap = finishedOpts.toMap

      val results = terminals
        .flatMap { t =>
          sortedGroups.lookupKey(t).flatMap { t0 =>
            finishedOptsMap(t) match {
              case None => Some((t0, Aborted))
              case Some(res) => res.newResults.get(t0).map(r => (t0, r))
            }
          }
        }
        .toMap

      timeLog.close()

      Evaluator.Results(
        goals.indexed.map(results(_).map(_._1)),
        finishedOpts.map(_._2).flatMap(_.toSeq.flatMap(_.newEvaluated)),
        transitive,
        getFailing(sortedGroups, results),
        results.map { case (k, v) => (k, v.map(_._1)) }
      )
    } finally threadPool.shutdown()
  }

  // those result which are inputs but not contained in this terminal group
  protected def evaluateGroupCached(
      terminal: Terminal,
      group: Agg[Task[_]],
      results: collection.Map[Task[_], mill.api.Result[(Any, Int)]],
      counterMsg: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger
  ): Evaluated = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.iterator.map(_.sideHash)
    )

    val scriptsHash = {
      val classes = new Loose.Agg.Mutable[String]()
      group.iterator.flatMap(t => Iterator(t) ++ t.inputs).foreach {
        case namedTask: NamedTask[_] =>
          val cls = namedTask.ctx.enclosingCls.getName
          val normalized = AmmoniteUtils.normalizeAmmoniteImportPath(cls)
          classes.append(normalized)
        case _ =>
      }
      val importClasses = importTree.filter(e => classes.contains(e.cls))
      val dependendentScripts = Graph.transitiveNodes(importClasses).map(_.cls)
      val dependendentScriptsSig = dependendentScripts.map(s => s -> scriptsSigMap(s))
      dependendentScriptsSig.hashCode()
    }

    val classLoaderSigHash =
      if (importTree.nonEmpty) {
        externalClassLoaderSigHash + scriptsHash
      } else {
        // We fallback to the old mechanism when the importTree was not populated
        classLoaderSig.hashCode()
      }

    val inputsHash = externalInputsHash + sideHashes + classLoaderSigHash

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
        Evaluated(newResults, newEvaluated.toSeq, false)

      case lntRight @ Right(labelledNamedTask) =>
        val out =
          if (!labelledNamedTask.task.ctx.external) outPath
          else externalOutPath

        val paths = EvaluatorPaths.resolveDestPaths(
          out,
          destSegments(labelledNamedTask)
        )

        val cached: Option[(Any, Int)] = for {
          cached <-
            try Some(upickle.default.read[Evaluator.Cached](paths.meta.toIO))
            catch {
              case NonFatal(_) => None
            }
          if cached.inputsHash == inputsHash
          reader <- labelledNamedTask.format
          parsed <-
            try Some(upickle.default.read(cached.value)(reader))
            catch {
              case NonFatal(_) => None
            }
        } yield (parsed, cached.valueHash)

        val previousWorker = labelledNamedTask.task.asWorker.flatMap { w =>
          workerCache.synchronized { workerCache.get(w.ctx.segments) }
        }
        val upToDateWorker: Option[Any] = previousWorker.flatMap {
          case (`inputsHash`, upToDate) =>
            // worker cached and up-to-date
            Some(upToDate)
          case (_, obsolete: AutoCloseable) =>
            // worker cached but obsolete, needs to be closed
            try {
              logger.debug(s"Closing previous worker: ${labelledNamedTask.segments.render}")
              obsolete.close()
            } catch {
              case NonFatal(e) =>
                logger.error(
                  s"${labelledNamedTask.segments.render}: Errors while closing obsolete worker: ${e.getMessage()}"
                )
            }
            // make sure, we can no longer re-use a closed worker
            labelledNamedTask.task.asWorker.foreach { w =>
              workerCache.synchronized { workerCache.remove(w.ctx.segments) }
            }
            None
          case _ =>
            // worker not cached or obsolete
            None
        }

        upToDateWorker.map((_, inputsHash)) orElse cached match {
          case Some((v, hashCode)) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], mill.api.Result[(Any, Int)]]
            newResults(labelledNamedTask.task) = mill.api.Result.Success((v, hashCode))

            Evaluated(newResults, Nil, cached = true)

          case _ =>
            // uncached
            if (labelledNamedTask.task.flushDest) os.remove.all(paths.dest)

            val targetLabel = printTerm(lntRight)

            val (newResults, newEvaluated) =
              Evaluator.dynamicTickerPrefix.withValue(s"[$counterMsg] $targetLabel > ") {
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

            newResults(labelledNamedTask.task) match {
              case mill.api.Result.Failure(_, Some((v, _))) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case mill.api.Result.Success((v, _)) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                os.remove.all(paths.meta)
            }

            Evaluated(newResults, newEvaluated.toSeq, cached = false)
        }
    }
  }

  def destSegments(labelledTask: Labelled[_]): Segments = {
    labelledTask.task.ctx.foreign match {
      case Some(foreignSegments) =>
        foreignSegments ++ labelledTask.segments

      case None =>
        labelledTask.segments
    }
  }

  def handleTaskResult(
      v: Any,
      hashCode: Int,
      metaPath: os.Path,
      inputsHash: Int,
      labelledNamedTask: Labelled[_]
  ): Unit = {
    labelledNamedTask.task.asWorker match {
      case Some(w) =>
        workerCache.synchronized {
          workerCache.update(w.ctx.segments, (inputsHash, v))
        }
      case None =>
        val terminalResult = labelledNamedTask
          .writer
          .asInstanceOf[Option[upickle.default.Writer[Any]]]
          .map(w => upickle.default.writeJs(v)(w) -> v)

        for ((json, _) <- terminalResult) {
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

  protected def evaluateGroup(
      group: Agg[Task[_]],
      results: collection.Map[Task[_], mill.api.Result[(Any, Int)]],
      inputsHash: Int,
      paths: Option[EvaluatorPaths],
      maybeTargetLabel: Option[String],
      counterMsg: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger
  ): (mutable.LinkedHashMap[Task[_], mill.api.Result[(Any, Int)]], mutable.Buffer[Task[_]]) = {

    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], mill.api.Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    // should we log progress?
    val logRun = maybeTargetLabel.isDefined && {
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)
      inputResults.forall(_.isInstanceOf[mill.api.Result.Success[_]])
    }

    val tickerPrefix = maybeTargetLabel.map { targetLabel =>
      val prefix = s"[$counterMsg] $targetLabel "
      if (logRun) logger.ticker(prefix)
      prefix + "| "
    }

    val multiLogger = new ProxyLogger(resolveLogger(paths.map(_.log), logger)) {
      override def ticker(s: String): Unit = {
        super.ticker(tickerPrefix.getOrElse("") + s)
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
        .map { x => newResults.getOrElse(x, results(x)) }
        .collect { case mill.api.Result.Success((v, _)) => v }

      val res =
        if (targetInputValues.length != task.inputs.length) mill.api.Result.Skipped
        else {
          val args = new Ctx(
            args = targetInputValues.toArray[Any].toIndexedSeq,
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

          val out = System.out
          val in = System.in
          val err = System.err
          try {
            System.setIn(multiLogger.inStream)
            System.setErr(multiLogger.errorStream)
            System.setOut(multiLogger.outputStream)
            Console.withIn(multiLogger.inStream) {
              Console.withOut(multiLogger.outputStream) {
                Console.withErr(multiLogger.errorStream) {
                  try task.evaluate(args)
                  catch {
                    case NonFatal(e) =>
                      mill.api.Result.Exception(
                        e,
                        new OuterStack(new Exception().getStackTrace.toIndexedSeq)
                      )
                  }
                }
              }
            }
          } finally {
            System.setErr(err)
            System.setOut(out)
            System.setIn(in)
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

    if (!failFast) maybeTargetLabel.foreach { targetLabel =>
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[_]])
      if (taskFailed) {
        logger.error(s"[${counterMsg}] ${targetLabel} failed")
      }
    }

    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[os.Path], logger: mill.api.Logger): mill.api.Logger =
    logPath match {
      case None => logger
      case Some(path) => new MultiLogger(
          logger.colored,
          logger,
          // we always enable debug here, to get some more context in log files
          new FileLogger(logger.colored, path, debugEnabled = true),
          logger.inStream,
          debugEnabled = logger.debugEnabled
        )
    }

  // TODO: we could track the deps of the dependency chain, to prioritize tasks with longer chain
  // TODO: we could also track the number of other tasks that depends on a task to prioritize
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

  def printTerm(term: Terminal): String = term match {
    case Left(task) => task.toString()
    case Right(labelledNamedTask) =>
      val Seq(first, rest @ _*) = destSegments(labelledNamedTask).value
      val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(s) => "[" + s.mkString(",") + "]"
      }
      msgParts.mkString
  }

  override def toString(): String = {
    s"""Evaluator(
       |  home = $home,
       |  outPath = $outPath,
       |  externalOutPath = $externalOutPath,
       |  rootModule = $rootModule,
       |  baseLogger = $baseLogger,
       |  classLoaderSig = $classLoaderSig,
       |  workerCache = $workerCache,
       |  env = $env,
       |  failFast = $failFast,
       |  threadCount = $threadCount,
       |  importTree = $importTree
       |)""".stripMargin
  }

  private def copy(
      home: os.Path = this.home,
      outPath: os.Path = this.outPath,
      externalOutPath: os.Path = this.externalOutPath,
      rootModule: mill.define.BaseModule = this.rootModule,
      baseLogger: ColorLogger = this.baseLogger,
      classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = this.classLoaderSig,
      workerCache: mutable.Map[Segments, (Int, Any)] = this.workerCache,
      env: Map[String, String] = this.env,
      failFast: Boolean = this.failFast,
      threadCount: Option[Int] = this.threadCount,
      importTree: Seq[ScriptNode] = this.importTree
  ): Evaluator = new Evaluator(
    home,
    outPath,
    externalOutPath,
    rootModule,
    baseLogger,
    classLoaderSig,
    workerCache,
    env,
    failFast,
    threadCount,
    importTree
  )

  def withHome(home: os.Path): Evaluator = copy(home = home)
  def withOutPath(outPath: os.Path): Evaluator = copy(outPath = outPath)
  def withExternalOutPath(externalOutPath: os.Path): Evaluator =
    copy(externalOutPath = externalOutPath)
  def withRootModule(rootModule: mill.define.BaseModule): Evaluator =
    copy(rootModule = rootModule)
  def withBaseLogger(baseLogger: ColorLogger): Evaluator = copy(baseLogger = baseLogger)
  def withClassLoaderSig(classLoaderSig: Seq[(Either[String, java.net.URL], Long)]): Evaluator =
    copy(classLoaderSig = classLoaderSig)
  def withWorkerCache(workerCache: mutable.Map[Segments, (Int, Any)]): Evaluator =
    copy(workerCache = workerCache)
  def withEnv(env: Map[String, String]): Evaluator = copy(env = env)
  def withFailFast(failFast: Boolean): Evaluator = copy(failFast = failFast)
  def withThreadCount(threadCount: Option[Int]): Evaluator = copy(threadCount = threadCount)
  def withImportTree(importTree: Seq[ScriptNode]): Evaluator = copy(importTree = importTree)
}

object Evaluator {

  /**
   * A terminal or terminal target is some important work unit, that in most cases has a name (Right[Labelled])
   * or was directly called by the user (Left[Task]).
   * It's a T, T.worker, T.input, T.source, T.sources, T.persistent
   */
  type Terminal = Either[Task[_], Labelled[Any]]

  /**
   * A terminal target with all it's inner tasks.
   * To implement a terminal target, one can delegate to other/inner tasks (T.task), those are contained in
   * the 2nd parameter of the tuple.
   */
  type TerminalGroup = (Terminal, Agg[Task[_]])

  case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)
  object Cached {
    implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
  }

  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new ThreadLocal[mill.eval.Evaluator]

  val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  // check if the build itself has changed
  def classLoaderSig: Seq[(Either[String, URL], Long)] =
    Thread.currentThread().getContextClassLoader match {
      case scl: SpecialClassLoader => scl.classpathSignature
      case ucl: URLClassLoader =>
        SpecialClassLoader.initialClasspathSignature(ucl)
      case _ => Nil
    }

  case class Timing(label: String, millis: Int, cached: Boolean)

  object Timing {
    implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
  }

  def writeTimings(
      timings: Seq[(Either[Task[_], Labelled[_]], Int, Boolean)],
      outPath: os.Path
  ): Unit = {
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

  case class Results(
      rawValues: Seq[mill.api.Result[Any]],
      evaluated: Agg[Task[_]],
      transitive: Agg[Task[_]],
      failing: MultiBiMap[Either[Task[_], Labelled[_]], mill.api.Result.Failing[_]],
      results: collection.Map[Task[_], mill.api.Result[Any]]
  ) {
    def values: Seq[Any] = rawValues.collect { case mill.api.Result.Success(v) => v }
  }

  def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val seen = collection.mutable.Set.empty[Segments]
    val overridden = collection.mutable.Set.empty[Task[_]]
    topoSorted.values.reverse.iterator.foreach {
      case x: NamedTask[_] =>
        if (!seen.contains(x.ctx.segments)) seen.add(x.ctx.segments)
        else overridden.add(x)
      case _ => // donothing
    }

    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted) {
      // important: all named tasks and those explicitly requested
      case t: NamedTask[Any] =>
        val segments = t.ctx.segments
        Right(
          Labelled(
            t,
            if (!overridden(t)) segments
            else {
              val Segment.Label(tName) = segments.value.last
              Segments(
                segments.value.init ++
                  Seq(Segment.Label(tName + ".super")) ++
                  t.ctx.enclosing.split("[.# ]").map(Segment.Label): _*
              )
            }
          )
        )
      case t if goals.contains(t) => Left(t)
    }

    (sortedGroups, transitive)
  }

  case class Evaluated(
      newResults: collection.Map[Task[_], mill.api.Result[(Any, Int)]],
      newEvaluated: Seq[Task[_]],
      cached: Boolean
  )

  // Increment the counter message by 1 to go from 1/10 to 10/10
  class NextCounterMsg(taskCount: Int) {
    var counter: Int = 0

    def apply(): String = {
      counter += 1
      s"${counter}/${taskCount}"
    }
  }

  def writeTracings(tracings: Seq[TraceEvent], outPath: os.Path): Unit = {
    os.write.over(
      outPath / "mill-par-profile.json",
      upickle.default.stream(tracings, indent = 2)
    )
  }

  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: Results => Throwable) {
    def apply[T: ClassTag](task: Task[T]): T =
      evaluator.evaluate(Agg(task)) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r =>
          // Input is a single-item Agg, so we also expect a single-item result
          val Seq(e: T) = r.values
          e
      }
    def apply[T: ClassTag](tasks: Seq[Task[T]]): Seq[T] =
      evaluator.evaluate(tasks) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r => r.values.asInstanceOf[Seq[T]]
      }
  }

  /**
   * Evaluate given task(s) and return the successful result(s), or throw an exception.
   */
  def evalOrThrow(
      evaluator: Evaluator,
      exceptionFactory: Results => Throwable =
        r => new Exception(s"Failure during task evaluation: ${Evaluator.formatFailing(r)}")
  ): EvalOrThrow = new EvalOrThrow(evaluator, exceptionFactory)

  def formatFailing(evaluated: Evaluator.Results): String = {
    (for ((k, fs) <- evaluated.failing.items())
      yield {
        val ks = k match {
          case Left(t) => t.toString
          case Right(t) => t.segments.render
        }
        val fss = fs.map {
          case mill.api.Result.Exception(t, outerStack) =>
            var current = List(t)
            while (current.head.getCause != null) {
              current = current.head.getCause :: current
            }
            current.reverse
              .flatMap(ex =>
                Seq(ex.toString) ++
                  ex.getStackTrace.dropRight(outerStack.value.length).map("    " + _)
              )
              .mkString("\n")
          case mill.api.Result.Failure(t, _) => t
        }
        s"$ks ${fss.iterator.mkString(", ")}"
      }).mkString("\n")
  }

  private val dynamicTickerPrefix = new DynamicVariable("")

  def apply(
      home: os.Path,
      outPath: os.Path,
      externalOutPath: os.Path,
      rootModule: mill.define.BaseModule,
      baseLogger: ColorLogger
  ): Evaluator = new Evaluator(
    _home = home,
    _outPath = outPath,
    _externalOutPath = externalOutPath,
    _rootModule = rootModule,
    _baseLogger = baseLogger,
    _classLoaderSig = Evaluator.classLoaderSig,
    _workerCache = mutable.Map.empty,
    _env = Evaluator.defaultEnv,
    _failFast = true,
    _threadCount = Some(1),
    _importTree = Seq.empty
  )
}
