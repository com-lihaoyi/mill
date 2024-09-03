package mill.eval

import mill.api.Result.{OuterStack, Success}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.eval.Evaluator.TaskResult
import mill.util._

import java.io.PrintStream
import java.lang.reflect.Method
import scala.collection.mutable
import scala.reflect.NameTransformer.encode
import scala.util.control.NonFatal
import scala.util.{DynamicVariable, Using}

/**
 * Logic around evaluating a single group, which is a collection of [[Task]]s
 * with a single [[Terminal]].
 */
private[mill] trait GroupEvaluator {
  def home: os.Path
  def workspace: os.Path
  def outPath: os.Path
  def externalOutPath: os.Path
  def rootModule: mill.define.BaseModule
  def classLoaderSigHash: Int
  def classLoaderIdentityHash: Int
  def workerCache: mutable.Map[Segments, (Int, Val)]
  def env: Map[String, String]
  def failFast: Boolean
  def threadCount: Option[Int]
  def scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])]
  def methodCodeHashSignatures: Map[String, Int]
  def disableCallgraphInvalidation: Boolean

  lazy val constructorHashSignatures: Map[String, Seq[(String, Int)]] = methodCodeHashSignatures
    .toSeq
    .collect { case (method @ s"$prefix#<init>($args)void", hash) => (prefix, method, hash) }
    .groupMap(_._1)(t => (t._2, t._3))

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  /**
   * Synchronize evaluations of the same terminal task.
   * This isn't necessarily needed for normal Mill executions,
   * but in an BSP context, where multiple requests where handled concurrently in the same Mill instance,
   * evaluating the same task concurrently  can happen.
   *
   * We don't synchronize multiple Mill-instances (e.g. run in two shells)
   * or multiple evaluator-instances (which should have different `out`-dirs anyway.
   */
  private object synchronizedEval {
    private val keyLock = new KeyLock[Segments]()
    def apply[T](terminal: Terminal, onCollision: Option[() => Unit] = None)(f: => T): T =
      terminal match {
        case t: Terminal.Task[_] =>
          // A un-labelled terminal task won't be synchronized
          // as there is no filesystem cache region assigned to it
          f
        case Terminal.Labelled(_, segments) =>
          // A labelled terminal needs synchronization due to
          // a shared cache region in the filesystem
          Using.resource(keyLock.lock(segments, onCollision)) { _ =>
            f
          }
      }
  }

  // those result which are inputs but not contained in this terminal group
  def evaluateGroupCached(
      terminal: Terminal,
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      counterMsg: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger,
      classToTransitiveClasses: Map[Class[_], IndexedSeq[Class[_]]],
      allTransitiveClassMethods: Map[Class[_], Map[String, Method]]
  ): GroupEvaluator.Results = synchronizedEval(
    terminal,
    onCollision =
      Some(() => logger.debug(s"Waiting for concurrently executing task ${terminal.render}"))
  ) {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).result.asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.iterator.map(_.sideHash)
    )

    val scriptsHash = group
      .iterator
      .collect {
        case namedTask: NamedTask[_] =>
          val encodedTaskName = encode(namedTask.ctx.segment.pathSegments.head)
          val methodOpt = for {
            parentCls <- classToTransitiveClasses(namedTask.ctx.enclosingCls).iterator
            m <- allTransitiveClassMethods(parentCls).get(encodedTaskName)
          } yield m

          val methodClass = methodOpt
            .nextOption()
            .getOrElse(throw new MillException(
              s"Could not detect the parent class of target ${namedTask}. " +
                s"Please report this at ${BuildInfo.millReportNewIssueUrl} . " +
                s"As a workaround, you can run Mill with `--disable-callgraph-invalidation` option."
            ))
            .getDeclaringClass.getName

          val name = namedTask.ctx.segment.pathSegments.last
          val expectedName = methodClass + "#" + name + "()mill.define.Target"

          // We not only need to look up the code hash of the Target method being called,
          // but also the code hash of the constructors required to instantiate the Module
          // that the Target is being called on. This can be done by walking up the nested
          // modules and looking at their constructors (they're `object`s and should each
          // have only one)
          val allEnclosingModules = Vector.unfold(namedTask.ctx) {
            case null => None
            case ctx =>
              ctx.enclosingModule match {
                case null => None
                case m: mill.define.Module => Some((m, m.millOuterCtx))
                case unknown =>
                  throw new MillException(s"Unknown ctx of target ${namedTask}: $unknown")
              }
          }

          val constructorHashes = allEnclosingModules
            .map(m =>
              constructorHashSignatures.get(m.getClass.getName) match {
                case Some(Seq((singleMethod, hash))) => hash
                case Some(multiple) => throw new MillException(
                    s"Multiple constructors found for module $m: ${multiple.mkString(",")}"
                  )
                case None => 0
              }
            )

          methodCodeHashSignatures.get(expectedName) ++ constructorHashes
      }
      .flatten
      .sum

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
        GroupEvaluator.Results(newResults, newEvaluated.toSeq, null, inputsHash, -1)

      case labelled: Terminal.Labelled[_] =>
        val out =
          if (!labelled.task.ctx.external) outPath
          else externalOutPath

        val paths = EvaluatorPaths.resolveDestPaths(
          out,
          Terminal.destSegments(labelled)
        )

        val cached = loadCachedJson(logger, inputsHash, labelled, paths)

        val upToDateWorker = loadUpToDateWorker(logger, inputsHash, labelled)

        upToDateWorker.map((_, inputsHash)) orElse cached.flatMap(_._2) match {
          case Some((v, hashCode)) =>
            val res = Result.Success((v, hashCode))
            val newResults: Map[Task[_], TaskResult[(Val, Int)]] =
              Map(labelled.task -> TaskResult(res, () => res))

            GroupEvaluator.Results(
              newResults,
              Nil,
              cached = true,
              inputsHash,
              -1
            )

          case _ =>
            // uncached
            if (labelled.task.flushDest) os.remove.all(paths.dest)

            val targetLabel = Terminal.printTerm(terminal)

            val (newResults, newEvaluated) =
              GroupEvaluator.dynamicTickerPrefix.withValue(s"[$counterMsg] $targetLabel > ") {
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

            GroupEvaluator.Results(
              newResults,
              newEvaluated.toSeq,
              cached = if (labelled.task.isInstanceOf[InputImpl[_]]) null else false,
              inputsHash,
              cached.map(_._1).getOrElse(-1)
            )
        }
    }

  }

  private def evaluateGroup(
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

        override def rawOutputStream: PrintStream = logger.rawOutputStream
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
            val args = new mill.api.Ctx(
              args = targetInputValues.map(_.value).toIndexedSeq,
              dest0 = () =>
                paths match {
                  case Some(dest) =>
                    if (usedDest.isEmpty) os.makeDir.all(dest.dest)
                    usedDest = Some((task, new Exception().getStackTrace))
                    dest.dest

                  case None => throw new Exception("No `dest` folder available here")
                },
              log = multiLogger,
              home = home,
              env = env,
              reporter = reporter,
              testReporter = testReporter,
              workspace = workspace
            ) with mill.api.Ctx.Jobs {
              override def jobs: Int = effectiveThreadCount
            }

            mill.api.SystemStreams.withStreams(multiLogger.systemStreams) {
              try task.evaluate(args).map(Val(_))
              catch {
                case f: Result.Failing[Val] => f
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

  // Include the classloader identity hash as part of the worker hash. This is
  // because unlike other targets, workers are long-lived in memory objects,
  // and are not re-instantiated every run. Thus we need to make sure we
  // invalidate workers in the scenario where a the worker classloader is
  // re-created - so the worker *class* changes - but the *value* inputs to the
  // worker does not change. This typically happens when the worker class is
  // brought in via `import $ivy`, since the class then comes from the
  // non-bootstrap classloader which can be re-created when the `build.mill` file
  // changes.
  //
  // We do not want to do this for normal targets, because those are always
  // read from disk and re-instantiated every time, so whether the
  // classloader/class is the same or different doesn't matter.
  def workerCacheHash(inputHash: Int): Int = inputHash + classLoaderIdentityHash

  private def handleTaskResult(
      v: Val,
      hashCode: Int,
      metaPath: os.Path,
      inputsHash: Int,
      labelled: Terminal.Labelled[_]
  ): Unit = {
    labelled.task.asWorker match {
      case Some(w) =>
        workerCache.synchronized {
          workerCache.update(w.ctx.segments, (workerCacheHash(inputsHash), v))
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

  private def loadCachedJson(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      paths: EvaluatorPaths
  ): Option[(Int, Option[(Val, Int)])] = {
    for {
      cached <-
        try Some(upickle.default.read[Evaluator.Cached](paths.meta.toIO))
        catch {
          case NonFatal(_) => None
        }
    } yield (
      cached.inputsHash,
      for {
        _ <- Option.when(cached.inputsHash == inputsHash)(())
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
    )
  }

  private def loadUpToDateWorker(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_]
  ): Option[Val] = {
    labelled.task.asWorker
      .flatMap { w =>
        workerCache.synchronized {
          workerCache.get(w.ctx.segments)
        }
      }
      .flatMap {
        case (cachedHash, upToDate)
            if cachedHash == workerCacheHash(inputsHash) =>
          Some(upToDate) // worker cached and up-to-date

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
}

private[mill] object GroupEvaluator {
  val dynamicTickerPrefix = new DynamicVariable("")

  case class Results(
      newResults: Map[Task[_], TaskResult[(Val, Int)]],
      newEvaluated: Seq[Task[_]],
      cached: java.lang.Boolean,
      inputsHash: Int,
      previousInputsHash: Int
  )
}
