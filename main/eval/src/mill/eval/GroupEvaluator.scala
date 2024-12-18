package mill.eval

import mill.api.Result.{OuterStack, Success}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.eval.Evaluator.TaskResult
import mill.util._

import java.lang.reflect.Method
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.hashing.MurmurHash3

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
  def disableCallgraph: Boolean
  def systemExit: Int => Nothing
  def exclusiveSystemStreams: SystemStreams

  lazy val constructorHashSignatures: Map[String, Seq[(String, Int)]] =
    CodeSigUtils.constructorHashSignatures(methodCodeHashSignatures)

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  // those result which are inputs but not contained in this terminal group
  def evaluateGroupCached(
      terminal: Terminal,
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      countMsg: String,
      verboseKeySuffix: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger,
      classToTransitiveClasses: Map[Class[_], IndexedSeq[Class[_]]],
      allTransitiveClassMethods: Map[Class[_], Map[String, Method]],
      executionContext: mill.api.Ctx.Fork.Api,
      exclusive: Boolean
  ): GroupEvaluator.Results = {
    logger.withPrompt {
      val externalInputsHash = MurmurHash3.orderedHash(
        group.items.flatMap(_.inputs).filter(!group.contains(_))
          .flatMap(results(_).result.asSuccess.map(_.value._2))
      )

      val sideHashes = MurmurHash3.orderedHash(group.iterator.map(_.sideHash))

      val scriptsHash =
        if (disableCallgraph) 0
        else MurmurHash3.orderedHash(
          group
            .iterator
            .collect { case namedTask: NamedTask[_] =>
              CodeSigUtils.codeSigForTask(
                namedTask,
                classToTransitiveClasses,
                allTransitiveClassMethods,
                methodCodeHashSignatures,
                constructorHashSignatures
              )
            }
            .flatten
        )

      val inputsHash = externalInputsHash + sideHashes + classLoaderSigHash + scriptsHash

      terminal match {
        case Terminal.Task(task) =>
          val (newResults, newEvaluated) = evaluateGroup(
            group,
            results,
            inputsHash,
            paths = None,
            maybeTargetLabel = None,
            counterMsg = countMsg,
            verboseKeySuffix = verboseKeySuffix,
            zincProblemReporter,
            testReporter,
            logger,
            executionContext,
            exclusive
          )
          GroupEvaluator.Results(
            newResults,
            newEvaluated.toSeq,
            null,
            inputsHash,
            -1,
            valueHashChanged = false
          )

        case labelled: Terminal.Labelled[_] =>
          val out = if (!labelled.task.ctx.external) outPath else externalOutPath
          val paths = EvaluatorPaths.resolveDestPaths(out, Terminal.destSegments(labelled))
          val cached = loadCachedJson(logger, inputsHash, labelled, paths)

          // `cached.isEmpty` means worker metadata file removed by user so recompute the worker
          val upToDateWorker = loadUpToDateWorker(logger, inputsHash, labelled, cached.isEmpty)

          val cachedValueAndHash =
            upToDateWorker.map((_, inputsHash))
              .orElse(cached.flatMap { case (inputHash, valOpt, valueHash) =>
                valOpt.map((_, valueHash))
              })

          cachedValueAndHash match {
            case Some((v, hashCode)) =>
              val res = Result.Success((v, hashCode))
              val newResults: Map[Task[_], TaskResult[(Val, Int)]] =
                Map(labelled.task -> TaskResult(res, () => res))

              GroupEvaluator.Results(
                newResults,
                Nil,
                cached = true,
                inputsHash,
                -1,
                valueHashChanged = false
              )

            case _ =>
              // uncached
              if (labelled.task.flushDest) os.remove.all(paths.dest)

              val (newResults, newEvaluated) =
                evaluateGroup(
                  group,
                  results,
                  inputsHash,
                  paths = Some(paths),
                  maybeTargetLabel = Some(terminal.render),
                  counterMsg = countMsg,
                  verboseKeySuffix = verboseKeySuffix,
                  zincProblemReporter,
                  testReporter,
                  logger,
                  executionContext,
                  exclusive
                )

              val valueHash = newResults(labelled.task) match {
                case TaskResult(Result.Failure(_, Some((v, _))), _) =>
                  val valueHash = getValueHash(v, terminal.task, inputsHash)
                  handleTaskResult(v, valueHash, paths.meta, inputsHash, labelled)
                  valueHash

                case TaskResult(Result.Success((v, _)), _) =>
                  val valueHash = getValueHash(v, terminal.task, inputsHash)
                  handleTaskResult(v, valueHash, paths.meta, inputsHash, labelled)
                  valueHash

                case _ =>
                  // Wipe out any cached meta.json file that exists, so
                  // a following run won't look at the cached metadata file and
                  // assume it's associated with the possibly-borked state of the
                  // destPath after an evaluation failure.
                  os.remove.all(paths.meta)
                  0
              }

              GroupEvaluator.Results(
                newResults,
                newEvaluated.toSeq,
                cached = if (labelled.task.isInstanceOf[InputImpl[_]]) null else false,
                inputsHash,
                cached.map(_._1).getOrElse(-1),
                !cached.map(_._3).contains(valueHash)
              )
          }
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
      verboseKeySuffix: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger,
      executionContext: mill.api.Ctx.Fork.Api,
      exclusive: Boolean
  ): (Map[Task[_], TaskResult[(Val, Int)]], mutable.Buffer[Task[_]]) = {

    def computeAll() = {
      val newEvaluated = mutable.Buffer.empty[Task[_]]
      val newResults = mutable.Map.empty[Task[_], Result[(Val, Int)]]

      val nonEvaluatedTargets = group.indexed.filterNot(results.contains)
      val multiLogger = resolveLogger(paths.map(_.log), logger)

      var usedDest = Option.empty[os.Path]
      for (task <- nonEvaluatedTargets) {
        newEvaluated.append(task)
        val targetInputValues = task.inputs
          .map { x => newResults.getOrElse(x, results(x).result) }
          .collect { case Result.Success((v, _)) => v }

        def makeDest() = this.synchronized {
          paths match {
            case Some(dest) =>
              if (usedDest.isEmpty) os.makeDir.all(dest.dest)
              usedDest = Some(dest.dest)
              dest.dest

            case None => throw new Exception("No `dest` folder available here")
          }
        }

        val res = {
          if (targetInputValues.length != task.inputs.length) Result.Skipped
          else {
            val args = new mill.api.Ctx(
              args = targetInputValues.map(_.value).toIndexedSeq,
              dest0 = () => makeDest(),
              log = multiLogger,
              home = home,
              env = env,
              reporter = reporter,
              testReporter = testReporter,
              workspace = workspace,
              systemExit = systemExit,
              fork = executionContext
            ) with mill.api.Ctx.Jobs {
              override def jobs: Int = effectiveThreadCount
            }

            def wrap[T](t: => T): T = {
              val (streams, destFunc) =
                if (exclusive) (exclusiveSystemStreams, () => workspace)
                else (multiLogger.systemStreams, () => makeDest())

              os.dynamicPwdFunction.withValue(destFunc) {
                SystemStreams.withStreams(streams) {
                  if (!exclusive) t
                  else {
                    logger.reportKey(Seq(counterMsg))
                    logger.withPromptPaused { t }
                  }
                }
              }
            }

            wrap {
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

        newResults(task) = for (v <- res) yield (v, getValueHash(v, task, inputsHash))
      }

      multiLogger.close()
      (newResults, newEvaluated)
    }

    val (newResults, newEvaluated) = computeAll()

    if (!failFast) maybeTargetLabel.foreach { targetLabel =>
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[_]])
      if (taskFailed) {
        logger.error(s"[$counterMsg] $targetLabel failed")
      }
    }

    (
      newResults
        .map { case (k, v) =>
          val recalc = () => computeAll()._1.apply(k)
          val taskResult = TaskResult(v, recalc)
          (k, taskResult)
        }
        .toMap,
      newEvaluated
    )
  }

  // Include the classloader identity hash as part of the worker hash. This is
  // because unlike other targets, workers are long-lived in memory objects,
  // and are not re-instantiated every run. Thus, we need to make sure we
  // invalidate workers in the scenario where a worker classloader is
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
    for (w <- labelled.task.asWorker)
      workerCache.synchronized {
        workerCache.update(w.ctx.segments, (workerCacheHash(inputsHash), v))
      }

    val terminalResult = labelled
      .task
      .writerOpt
      .map { w =>
        upickle.default.writeJs(v.value)(using w.asInstanceOf[upickle.default.Writer[Any]])
      }
      .orElse {
        labelled.task.asWorker.map { w =>
          ujson.Obj(
            "worker" -> ujson.Str(labelled.segments.render),
            "toString" -> ujson.Str(v.value.toString),
            "inputsHash" -> ujson.Num(inputsHash)
          )
        }
      }

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
  ): Option[(Int, Option[Val], Int)] = {
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
          try Some(upickle.default.read(cached.value)(using reader))
          catch {
            case e: PathRef.PathRefValidationException =>
              logger.debug(
                s"${labelled.segments.render}: re-evaluating; ${e.getMessage}"
              )
              None
            case NonFatal(_) => None
          }
      } yield Val(parsed),
      cached.valueHash
    )
  }

  def getValueHash(v: Val, task: Task[_], inputsHash: Int): Int = {
    if (task.isInstanceOf[Worker[_]]) inputsHash else v.##
  }
  private def loadUpToDateWorker(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      forceDiscard: Boolean
  ): Option[Val] = {
    labelled.task.asWorker
      .flatMap { w =>
        workerCache.synchronized {
          workerCache.get(w.ctx.segments)
        }
      }
      .flatMap {
        case (cachedHash, upToDate)
            if cachedHash == workerCacheHash(inputsHash) && !forceDiscard =>
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

  case class Results(
      newResults: Map[Task[_], TaskResult[(Val, Int)]],
      newEvaluated: Seq[Task[_]],
      cached: java.lang.Boolean,
      inputsHash: Int,
      previousInputsHash: Int,
      valueHashChanged: Boolean
  )
}
