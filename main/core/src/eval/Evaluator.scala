package mill.eval

import java.net.URLClassLoader

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal
import ammonite.runtime.SpecialClassLoader
import mill.api.{BspContext, DummyBspContext}
import mill.util.Router.EntryPoint
import mill.define.{Ctx => _, _}
import mill.api.Result.{Aborted, OuterStack, Success}
import mill.util
import mill.util._
import mill.api.Strict.Agg
import sbt.internal.inc.{CompilerArguments, ManagedLoggedReporter}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.testing.Event
import sbt.util.LogExchange

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

//  override def hashCode(): Int = task.hashCode()
}

case class Evaluator(home: os.Path,
                     outPath: os.Path,
                     externalOutPath: os.Path,
                     rootModule: mill.define.BaseModule,
                     log: Logger,
                     classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = Evaluator.classLoaderSig,
                     workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty,
                     env : Map[String, String] = Evaluator.defaultEnv,
                     failFast: Boolean = true
                    ){

  val classLoaderSignHash = classLoaderSig.hashCode()

  def evaluate(goals: Agg[Task[_]],
               reporter: Int => Option[ManagedLoggedReporter] = (int: Int) => Option.empty[ManagedLoggedReporter],
               bspContext: BspContext = DummyBspContext,
               logger: Logger = log): Evaluator.Results = {
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
      val (newResults, newEvaluated, cached) = evaluateGroupCached(
        terminal,
        group,
        results,
        counterMsg,
        reporter,
        bspContext,
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


  def evaluateGroupCached(terminal: Either[Task[_], Labelled[_]],
                          group: Agg[Task[_]],
                          results: collection.Map[Task[_], Result[(Any, Int)]],
                          counterMsg: String,
                          reporter: Int => Option[ManagedLoggedReporter],
                          bspContext: BspContext,
                          logger: Logger
                         ): (collection.Map[Task[_], Result[(Any, Int)]], Seq[Task[_]], Boolean) = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.toIterator.map(_.sideHash)
    )

    val inputsHash = externalInputsHash + sideHashes + classLoaderSignHash

    terminal match{
      case Left(task) =>
        val (newResults, newEvaluated) = evaluateGroup(
          group,
          results,
          inputsHash,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg,
          reporter,
          bspContext,
          logger
        )
        (newResults, newEvaluated, false)
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

            (newResults, Nil, true)

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
              reporter,
              bspContext,
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

            (newResults, newEvaluated, false)
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
            ),
            createFolders = true
          )
        }
    }
  }

  def evaluateGroup(group: Agg[Task[_]],
                    results: collection.Map[Task[_], Result[(Any, Int)]],
                    inputsHash: Int,
                    paths: Option[Evaluator.Paths],
                    maybeTargetLabel: Option[String],
                    counterMsg: String,
                    reporter: Int => Option[ManagedLoggedReporter],
                    bspContext: BspContext,
                    logger: Logger): (mutable.LinkedHashMap[Task[_], Result[(Any, Int)]], mutable.Buffer[Task[_]]) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    val tickerPrefix = maybeTargetLabel.map { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      val prefix = s"[$counterMsg] $targetLabel "
      if(logRun) logger.ticker(prefix)
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
            bspContext //new ManagedLoggedReporter(10, logger)
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

  def resolveLogger(logPath: Option[os.Path], logger: Logger): Logger = logPath match{
    case None => logger
    case Some(path) => MultiLogger(logger.colored, log, FileLogger(logger.colored, path, debugEnabled = true))
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
