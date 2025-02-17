package mill.exec

import mill.api.ExecResult.{OuterStack, Success}

import mill.api.*
import mill.define.*
import mill.internal.MultiLogger
import mill.internal.FileLogger

import java.lang.reflect.Method
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.hashing.MurmurHash3

/**
 * Logic around evaluating a single group, which is a collection of [[Task]]s
 * with a single [[Terminal]].
 */
private[mill] trait GroupExecution {
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
  def methodCodeHashSignatures: Map[String, Int]
  def systemExit: Int => Nothing
  def exclusiveSystemStreams: SystemStreams

  lazy val constructorHashSignatures: Map[String, Seq[(String, Int)]] =
    CodeSigUtils.constructorHashSignatures(methodCodeHashSignatures)

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  // those result which are inputs but not contained in this terminal group
  def executeGroupCached(
      terminal: Task[?],
      group: Seq[Task[?]],
      results: Map[Task[?], TaskResult[(Val, Int)]],
      countMsg: String,
      verboseKeySuffix: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger,
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, Method]],
      executionContext: mill.api.Ctx.Fork.Api,
      exclusive: Boolean
  ): GroupExecution.Results = {
    logger.withPrompt {
      val externalInputsHash = MurmurHash3.orderedHash(
        group.flatMap(_.inputs).filter(!group.contains(_))
          .flatMap(results(_).result.asSuccess.map(_.value._2))
      )

      val sideHashes = MurmurHash3.orderedHash(group.iterator.map(_.sideHash))

      val scriptsHash = MurmurHash3.orderedHash(
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

      val javaHomeHash = sys.props("java.home").hashCode
      val inputsHash =
        externalInputsHash + sideHashes + classLoaderSigHash + scriptsHash + javaHomeHash

      terminal match {

        case labelled: NamedTask[_] =>
          val out = if (!labelled.ctx.external) outPath else externalOutPath
          val paths = ExecutionPaths.resolveDestPaths(out, labelled.ctx.segments)
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
              val res = ExecResult.Success((v, hashCode))
              val newResults: Map[Task[?], TaskResult[(Val, Int)]] =
                Map(labelled -> TaskResult(res, () => res))

              GroupExecution.Results(
                newResults,
                Nil,
                cached = true,
                inputsHash,
                -1,
                valueHashChanged = false
              )

            case _ =>
              // uncached
              if (!labelled.persistent) os.remove.all(paths.dest)

              val (newResults, newEvaluated) =
                executeGroup(
                  group,
                  results,
                  inputsHash,
                  paths = Some(paths),
                  maybeTargetLabel = Some(terminal.toString),
                  counterMsg = countMsg,
                  zincProblemReporter,
                  testReporter,
                  logger,
                  executionContext,
                  exclusive
                )

              val valueHash = newResults(labelled) match {
                case TaskResult(ExecResult.Success((v, _)), _) =>
                  val valueHash = getValueHash(v, terminal, inputsHash)
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

              GroupExecution.Results(
                newResults,
                newEvaluated.toSeq,
                cached = if (labelled.isInstanceOf[InputImpl[?]]) null else false,
                inputsHash,
                cached.map(_._1).getOrElse(-1),
                !cached.map(_._3).contains(valueHash)
              )
          }
        case task =>
          val (newResults, newEvaluated) = executeGroup(
            group,
            results,
            inputsHash,
            paths = None,
            maybeTargetLabel = None,
            counterMsg = countMsg,
            zincProblemReporter,
            testReporter,
            logger,
            executionContext,
            exclusive
          )
          GroupExecution.Results(
            newResults,
            newEvaluated.toSeq,
            null,
            inputsHash,
            -1,
            valueHashChanged = false
          )

      }
    }
  }

  private def executeGroup(
      group: Seq[Task[?]],
      results: Map[Task[?], TaskResult[(Val, Int)]],
      inputsHash: Int,
      paths: Option[ExecutionPaths],
      maybeTargetLabel: Option[String],
      counterMsg: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger,
      executionContext: mill.api.Ctx.Fork.Api,
      exclusive: Boolean
  ): (Map[Task[?], TaskResult[(Val, Int)]], mutable.Buffer[Task[?]]) = {

    def computeAll() = {
      val newEvaluated = mutable.Buffer.empty[Task[?]]
      val newResults = mutable.Map.empty[Task[?], ExecResult[(Val, Int)]]

      val nonEvaluatedTargets = group.toIndexedSeq.filterNot(results.contains)
      val multiLogger = resolveLogger(paths.map(_.log), logger)

      var usedDest = Option.empty[os.Path]
      for (task <- nonEvaluatedTargets) {
        newEvaluated.append(task)
        val targetInputValues = task.inputs
          .map { x => newResults.getOrElse(x, results(x).result) }
          .collect { case ExecResult.Success((v, _)) => v }

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
          if (targetInputValues.length != task.inputs.length) ExecResult.Skipped
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
              try {

                task.evaluate(args) match {
                  case Result.Success(v) => ExecResult.Success(Val(v))
                  case Result.Failure(err) => ExecResult.Failure(err)
                }
              } catch {
                case ex: Result.Exception => ExecResult.Failure(ex.error)
                case NonFatal(e) =>
                  ExecResult.Exception(
                    e,
                    new OuterStack(new Exception().getStackTrace.toIndexedSeq)
                  )
                case e: Throwable => throw e
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
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[?]])
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
      labelled: NamedTask[?]
  ): Unit = {
    for (w <- labelled.asWorker)
      workerCache.synchronized {
        workerCache.update(w.ctx.segments, (workerCacheHash(inputsHash), v))
      }

    val terminalResult = labelled
      .writerOpt
      .map { w =>
        upickle.default.writeJs(v.value)(using w.asInstanceOf[upickle.default.Writer[Any]])
      }
      .orElse {
        labelled.asWorker.map { w =>
          ujson.Obj(
            "worker" -> ujson.Str(labelled.toString),
            "toString" -> ujson.Str(v.value.toString),
            "inputsHash" -> ujson.Num(inputsHash)
          )
        }
      }

    for (json <- terminalResult) {
      os.write.over(
        metaPath,
        upickle.default.stream(
          Cached(json, hashCode, inputsHash),
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
      labelled: NamedTask[?],
      paths: ExecutionPaths
  ): Option[(Int, Option[Val], Int)] = {
    for {
      cached <-
        try Some(upickle.default.read[Cached](paths.meta.toIO))
        catch {
          case NonFatal(_) => None
        }
    } yield (
      cached.inputsHash,
      for {
        _ <- Option.when(cached.inputsHash == inputsHash)(())
        reader <- labelled.readWriterOpt
        parsed <-
          try Some(upickle.default.read(cached.value)(using reader))
          catch {
            case e: PathRef.PathRefValidationException =>
              logger.debug(
                s"$labelled: re-evaluating; ${e.getMessage}"
              )
              None
            case NonFatal(_) => None
          }
      } yield Val(parsed),
      cached.valueHash
    )
  }

  def getValueHash(v: Val, task: Task[?], inputsHash: Int): Int = {
    if (task.isInstanceOf[Worker[?]]) inputsHash else v.##
  }
  private def loadUpToDateWorker(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: NamedTask[?],
      forceDiscard: Boolean
  ): Option[Val] = {
    labelled.asWorker
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
            logger.debug(s"Closing previous worker: $labelled")
            obsolete.close()
          } catch {
            case NonFatal(e) =>
              logger.error(
                s"$labelled: Errors while closing obsolete worker: ${e.getMessage()}"
              )
          }
          // make sure, we can no longer re-use a closed worker
          labelled.asWorker.foreach { w =>
            workerCache.synchronized {
              workerCache.remove(w.ctx.segments)
            }
          }
          None

        case _ => None // worker not cached or obsolete
      }
  }
}

private[mill] object GroupExecution {

  case class Results(
      newResults: Map[Task[?], TaskResult[(Val, Int)]],
      newEvaluated: Seq[Task[?]],
      cached: java.lang.Boolean,
      inputsHash: Int,
      previousInputsHash: Int,
      valueHashChanged: Boolean
  )
}
