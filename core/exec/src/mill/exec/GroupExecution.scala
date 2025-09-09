package mill.exec

import mill.api.ExecResult.{OuterStack, Success}

import mill.api.*
import mill.internal.MultiLogger
import mill.internal.FileLogger

import java.lang.reflect.Method
import java.util.concurrent.ThreadPoolExecutor
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.hashing.MurmurHash3
import mill.api.daemon.internal.{BaseModuleApi, CompileProblemReporter, EvaluatorApi, TestReporter}

/**
 * Logic around evaluating a single group, which is a collection of [[Task]]s
 * with a single [[Terminal]].
 */
private trait GroupExecution {
  def workspace: os.Path
  def outPath: os.Path
  def externalOutPath: os.Path
  def rootModule: BaseModuleApi
  def classLoaderSigHash: Int
  def classLoaderIdentityHash: Int

  /**
   * `String` is the worker name, `Int` is the worker hash, `Val` is the result of the worker invocation.
   */
  def workerCache: mutable.Map[String, (Int, Val)]

  def env: Map[String, String]
  def failFast: Boolean
  def ec: Option[ThreadPoolExecutor]
  def codeSignatures: Map[String, Int]
  def systemExit: ( /* reason */ String, /* exitCode */ Int) => Nothing
  def exclusiveSystemStreams: SystemStreams
  def getEvaluator: () => EvaluatorApi
  def headerData: String
  def offline: Boolean

  lazy val parsedHeaderData: Map[String, ujson.Value] = {
    import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
    val loaded = new Load(LoadSettings.builder().build()).loadFromString(headerData)
    // recursively convert java data structure to ujson.Value
    val envWithPwd = env ++ Seq(
      "PWD" -> workspace.toString,
      "PWD_URI" -> workspace.toURI.toString,
      "MILL_VERSION" -> mill.constants.BuildInfo.millVersion,
      "MILL_BIN_PLATFORM" -> mill.constants.BuildInfo.millBinPlatform
    )
    def rec(x: Any): ujson.Value = {
      import scala.jdk.CollectionConverters._
      x match {
        case d: java.util.Date => ujson.Str(d.toString)
        case s: String => ujson.Str(mill.constants.Util.interpolateEnvVars(s, envWithPwd.asJava))
        case d: Double => ujson.Num(d)
        case d: Int => ujson.Num(d)
        case d: Long => ujson.Num(d)
        case true => ujson.True
        case false => ujson.False
        case null => ujson.Null
        case m: java.util.Map[Object, Object] =>
          val scalaMap = m.asScala
          ujson.Obj.from(scalaMap.map { case (k, v) => (k.toString, rec(v)) })
        case l: java.util.List[Object] =>
          val scalaList: collection.Seq[Object] = l.asScala
          ujson.Arr.from(scalaList.map(rec))
      }
    }

    rec(loaded).objOpt.getOrElse(Map.empty[String, ujson.Value]).toMap
  }

  lazy val constructorHashSignatures: Map[String, Seq[(String, Int)]] =
    CodeSigUtils.constructorHashSignatures(codeSignatures)

  val effectiveThreadCount: Int =
    ec.map(_.getMaximumPoolSize).getOrElse(1)

  // those result which are inputs but not contained in this terminal group
  def executeGroupCached(
      terminal: Task[?],
      group: Seq[Task[?]],
      results: Map[Task[?], ExecResult[(Val, Int)]],
      countMsg: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: Logger,
      deps: Seq[Task[?]],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, Method]],
      executionContext: mill.api.TaskCtx.Fork.Api,
      exclusive: Boolean,
      upstreamPathRefs: Seq[PathRef]
  ): GroupExecution.Results = {

    val externalInputsHash = MurmurHash3.orderedHash(
      group.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).asSuccess.map(_.value._2))
    )

    val sideHashes = MurmurHash3.orderedHash(group.iterator.map(_.sideHash))

    val scriptsHash = MurmurHash3.orderedHash(
      group
        .iterator
        .collect { case namedTask: Task.Named[_] =>
          CodeSigUtils.codeSigForTask(
            namedTask,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            codeSignatures,
            constructorHashSignatures
          )
        }
        .flatten
    )

    val javaHomeHash = sys.props("java.home").hashCode
    val inputsHash =
      externalInputsHash + sideHashes + classLoaderSigHash + scriptsHash + javaHomeHash

    terminal match {

      case labelled: Task.Named[_] =>
        labelled.ctx.segments.value match {
          case Seq(Segment.Label(single)) if parsedHeaderData.contains(single) =>
            val jsonData = parsedHeaderData(single)
            val (resultData, serializedPaths) = PathRef.withSerializedPaths {
              upickle.read[Any](jsonData)(
                using labelled.readWriterOpt.get.asInstanceOf[upickle.Reader[Any]]
              )
            }
            GroupExecution.Results(
              Map(labelled -> ExecResult.Success(Val(resultData), resultData.##)),
              Nil,
              cached = true,
              inputsHash,
              -1,
              false,
              serializedPaths
            )
          case _ =>
            val out = if (!labelled.ctx.external) outPath else externalOutPath
            val paths = ExecutionPaths.resolve(out, labelled.ctx.segments)
            val cached = loadCachedJson(logger, inputsHash, labelled, paths)

            // `cached.isEmpty` means worker metadata file removed by user so recompute the worker
            val upToDateWorker = loadUpToDateWorker(logger, inputsHash, labelled, cached.isEmpty)

            val cachedValueAndHash =
              upToDateWorker.map(w => (w -> Nil, inputsHash))
                .orElse(cached.flatMap { case (_, valOpt, valueHash) =>
                  valOpt.map((_, valueHash))
                })

            cachedValueAndHash match {
              case Some(((v, serializedPaths), hashCode)) =>
                val res = ExecResult.Success((v, hashCode))
                val newResults: Map[Task[?], ExecResult[(Val, Int)]] =
                  Map(labelled -> res)

                GroupExecution.Results(
                  newResults,
                  Nil,
                  cached = true,
                  inputsHash,
                  -1,
                  valueHashChanged = false,
                  serializedPaths
                )

              case _ =>
                // uncached
                if (!labelled.persistent) os.remove.all(paths.dest)

                val (newResults, newEvaluated) =
                  executeGroup(
                    group = group,
                    results = results,
                    inputsHash = inputsHash,
                    paths = Some(paths),
                    taskLabelOpt = Some(terminal.toString),
                    counterMsg = countMsg,
                    reporter = zincProblemReporter,
                    testReporter = testReporter,
                    logger = logger,
                    executionContext = executionContext,
                    exclusive = exclusive,
                    deps = deps,
                    upstreamPathRefs = upstreamPathRefs,
                    terminal = labelled
                  )

                val (valueHash, serializedPaths) = newResults(labelled) match {
                  case ExecResult.Success((v, _)) =>
                    val valueHash = getValueHash(v, terminal, inputsHash)
                    val serializedPaths =
                      handleTaskResult(v, valueHash, paths.meta, inputsHash, labelled)
                    (valueHash, serializedPaths)

                  case _ =>
                    // Wipe out any cached meta.json file that exists, so
                    // a following run won't look at the cached metadata file and
                    // assume it's associated with the possibly-borked state of the
                    // destPath after an evaluation failure.
                    os.remove.all(paths.meta)
                    (0, Nil)
                }

                GroupExecution.Results(
                  newResults,
                  newEvaluated.toSeq,
                  cached = if (labelled.isInstanceOf[Task.Input[?]]) null else false,
                  inputsHash,
                  cached.map(_._1).getOrElse(-1),
                  !cached.map(_._3).contains(valueHash),
                  serializedPaths
                )
            }
        }
      case _ =>
        val (newResults, newEvaluated) = executeGroup(
          group = group,
          results = results,
          inputsHash = inputsHash,
          paths = None,
          taskLabelOpt = None,
          counterMsg = countMsg,
          reporter = zincProblemReporter,
          testReporter = testReporter,
          logger = logger,
          executionContext = executionContext,
          exclusive = exclusive,
          deps = deps,
          upstreamPathRefs = upstreamPathRefs,
          terminal = terminal
        )
        GroupExecution.Results(
          newResults,
          newEvaluated.toSeq,
          null,
          inputsHash,
          -1,
          valueHashChanged = false,
          serializedPaths = Nil
        )

    }
  }

  private def executeGroup(
      group: Seq[Task[?]],
      results: Map[Task[?], ExecResult[(Val, Int)]],
      inputsHash: Int,
      paths: Option[ExecutionPaths],
      taskLabelOpt: Option[String],
      counterMsg: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger,
      executionContext: mill.api.TaskCtx.Fork.Api,
      exclusive: Boolean,
      deps: Seq[Task[?]],
      upstreamPathRefs: Seq[PathRef],
      terminal: Task[?]
  ): (Map[Task[?], ExecResult[(Val, Int)]], mutable.Buffer[Task[?]]) = {
    val newEvaluated = mutable.Buffer.empty[Task[?]]
    val newResults = mutable.Map.empty[Task[?], ExecResult[(Val, Int)]]

    val nonEvaluatedTasks = group.toIndexedSeq.filterNot(results.contains)
    val (multiLogger, fileLoggerOpt) = resolveLogger(paths.map(_.log), logger)

    val destCreator = new GroupExecution.DestCreator(paths)

    for (task <- nonEvaluatedTasks) {
      newEvaluated.append(task)
      val taskInputValues = task.inputs
        .map { x => newResults.getOrElse(x, results(x)) }
        .collect { case ExecResult.Success((v, _)) => v }

      val res = {
        if (taskInputValues.length != task.inputs.length) ExecResult.Skipped
        else {
          val args = new mill.api.TaskCtx.Impl(
            args = taskInputValues.map(_.value).toIndexedSeq,
            dest0 = () => destCreator.makeDest(),
            log = multiLogger,
            env = env,
            reporter = reporter,
            testReporter = testReporter,
            workspace = workspace,
            _systemExitWithReason = systemExit,
            fork = executionContext,
            jobs = effectiveThreadCount,
            offline = offline
          )

          // Tasks must be allowed to write to upstream worker's dest folders, because
          // the point of workers is to manualy manage long-lived state which includes
          // state on disk.
          val validWriteDests =
            deps.collect { case n: Task.Worker[?] =>
              ExecutionPaths.resolve(outPath, n.ctx.segments).dest
            } ++
              paths.map(_.dest)

          val validReadDests = validWriteDests ++ upstreamPathRefs.map(_.path)

          GroupExecution.wrap(
            workspace,
            validWriteDests,
            validReadDests,
            exclusive,
            multiLogger,
            logger,
            exclusiveSystemStreams,
            counterMsg,
            destCreator,
            getEvaluator().asInstanceOf[Evaluator],
            terminal,
            rootModule.getClass.getClassLoader
          ) {
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

    fileLoggerOpt.foreach(_.close())

    if (!failFast) taskLabelOpt.foreach { taskLabel =>
      val taskFailed = newResults.exists(task => task._2.isInstanceOf[ExecResult.Failing[?]])
      if (taskFailed) {
        logger.error(s"$taskLabel failed")
      }
    }

    (newResults.toMap, newEvaluated)
  }

  // Include the classloader identity hash as part of the worker hash. This is
  // because unlike other tasks, workers are long-lived in memory objects,
  // and are not re-instantiated every run. Thus, we need to make sure we
  // invalidate workers in the scenario where a worker classloader is
  // re-created - so the worker *class* changes - but the *value* inputs to the
  // worker does not change. This typically happens when the worker class is
  // brought in via `//| mvnDeps`, since the class then comes from the
  // non-bootstrap classloader which can be re-created when the `build.mill` file
  // changes.
  //
  // We do not want to do this for normal tasks, because those are always
  // read from disk and re-instantiated every time, so whether the
  // classloader/class is the same or different doesn't matter.
  def workerCacheHash(inputHash: Int): Int = inputHash + classLoaderIdentityHash

  private def handleTaskResult(
      v: Val,
      hashCode: Int,
      metaPath: os.Path,
      inputsHash: Int,
      labelled: Task.Named[?]
  ): Seq[PathRef] = {
    for (w <- labelled.asWorker)
      workerCache.synchronized {
        workerCache.update(w.ctx.segments.render, (workerCacheHash(inputsHash), v))
      }

    def normalJson(w: upickle.Writer[?]) = PathRef.withSerializedPaths {
      upickle.writeJs(v.value)(using w.asInstanceOf[upickle.Writer[Any]])
    }
    lazy val workerJson = labelled.asWorker.map { _ =>
      ujson.Obj(
        "worker" -> ujson.Str(labelled.toString),
        "toString" -> ujson.Str(v.value.toString),
        "inputsHash" -> ujson.Num(inputsHash)
      ) -> Nil
    }

    val terminalResult: Option[(ujson.Value, Seq[PathRef])] = labelled
      .writerOpt
      .map(normalJson)
      .orElse(workerJson)

    terminalResult match {
      case Some((json, serializedPaths)) =>
        os.write.over(
          metaPath,
          upickle.stream(
            mill.api.Cached(json, hashCode, inputsHash),
            indent = 4
          ),
          createFolders = true
        )
        serializedPaths
      case _ =>
        Nil
    }
  }

  def resolveLogger(
      logPath: Option[os.Path],
      logger: mill.api.Logger
  ): (mill.api.Logger, Option[AutoCloseable]) =
    logPath match {
      case None => (logger, None)
      case Some(path) =>
        val fileLogger = new FileLogger(path)
        val multiLogger = new MultiLogger(
          logger,
          fileLogger,
          logger.streams.in
        )
        (multiLogger, Some(fileLogger))
    }

  private def loadCachedJson(
      logger: Logger,
      inputsHash: Int,
      labelled: Task.Named[?],
      paths: ExecutionPaths
  ): Option[(Int, Option[(Val, Seq[PathRef])], Int)] = {
    for {
      cached <-
        try Some(upickle.read[Cached](paths.meta.toIO))
        catch {
          case NonFatal(_) => None
        }
    } yield (
      cached.inputsHash,
      for {
        _ <- Option.when(cached.inputsHash == inputsHash)(())
        reader <- labelled.readWriterOpt
        (parsed, serializedPaths) <-
          try Some(PathRef.withSerializedPaths(upickle.read(cached.value)(using reader)))
          catch {
            case e: PathRef.PathRefValidationException =>
              logger.debug(
                s"$labelled: re-evaluating; ${e.getMessage}"
              )
              None
            case NonFatal(_) => None
          }
      } yield (Val(parsed), serializedPaths),
      cached.valueHash
    )
  }

  def getValueHash(v: Val, task: Task[?], inputsHash: Int): Int = {
    if (task.isInstanceOf[Task.Worker[?]]) inputsHash else v.##
  }
  private def loadUpToDateWorker(
      logger: Logger,
      inputsHash: Int,
      labelled: Task.Named[?],
      forceDiscard: Boolean
  ): Option[Val] = {
    labelled.asWorker
      .flatMap { w =>
        workerCache.synchronized {
          workerCache.get(w.ctx.segments.render)
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
              workerCache.remove(w.ctx.segments.render)
            }
          }
          None

        case _ => None // worker not cached or obsolete
      }
  }
}

private object GroupExecution {

  class DestCreator(paths: Option[ExecutionPaths]) {
    var usedDest = Option.empty[os.Path]

    def makeDest() = this.synchronized {
      paths match {
        case Some(dest) =>
          if (usedDest.isEmpty) os.makeDir.all(dest.dest)
          usedDest = Some(dest.dest)
          dest.dest

        case None => throw new Exception("No `dest` folder available here")
      }
    }
  }
  def wrap[T](
      workspace: os.Path,
      validWriteDests: Seq[os.Path],
      validReadDests: Seq[os.Path],
      exclusive: Boolean,
      multiLogger: Logger,
      logger: Logger,
      exclusiveSystemStreams: SystemStreams,
      counterMsg: String,
      destCreator: DestCreator,
      evaluator: Evaluator,
      terminal: Task[?],
      classLoader: ClassLoader
  )(t: => T): T = {
    val isCommand = terminal.isInstanceOf[Task.Command[?]]
    val isInput = terminal.isInstanceOf[Task.Input[?]]
    val executionChecker = new os.Checker {
      def onRead(path: os.ReadablePath): Unit = path match {
        case path: os.Path =>
          if (!isCommand && !isInput && mill.api.FilesystemCheckerEnabled.value) {
            if (path.startsWith(workspace) && !validReadDests.exists(path.startsWith(_))) {
              sys.error(
                s"Reading from ${path.relativeTo(workspace)} not allowed during execution of `$terminal`.\n" +
                  "You can only read files referenced by `Task.Source` or `Task.Sources`, or within a `Task.Input"
              )
            }
          }
        case _ =>
      }

      def onWrite(path: os.Path): Unit = {
        if (!isCommand && mill.api.FilesystemCheckerEnabled.value) {
          if (path.startsWith(workspace) && !validWriteDests.exists(path.startsWith(_))) {
            sys.error(
              s"Writing to ${path.relativeTo(workspace)} not allowed during execution of `$terminal`.\n" +
                "Normal `Task`s can only write to files within their `Task.dest` folder, only `Task.Command`s can write to other arbitrary files."
            )
          }
        }
      }
    }
    val (streams, destFunc) =
      if (exclusive) (exclusiveSystemStreams, () => workspace)
      else (multiLogger.streams, () => destCreator.makeDest())

    os.dynamicPwdFunction.withValue(destFunc) {
      os.checker.withValue(executionChecker) {
        mill.api.SystemStreamsUtils.withStreams(streams) {
          val exposedEvaluator =
            if (exclusive) evaluator.asInstanceOf[Evaluator]
            else new EvaluatorProxy(() =>
              sys.error(
                "No evaluator available here; Evaluator is only available in exclusive commands"
              )
            )

          Evaluator.withCurrentEvaluator(exposedEvaluator) {
            // Ensure the class loader used to load user code
            // is set as context class loader when running user code.
            // This is useful if users rely on libraries that look
            // for resources added by other libraries, by using
            // using java.util.ServiceLoader for example.
            mill.api.ClassLoader.withContextClassLoader(classLoader) {
              if (!exclusive) t
              else {
                logger.prompt.reportKey(Seq(counterMsg))
                logger.prompt.withPromptPaused {
                  t
                }
              }
            }
          }
        }
      }
    }
  }
  case class Results(
      newResults: Map[Task[?], ExecResult[(Val, Int)]],
      newEvaluated: Seq[Task[?]],
      cached: java.lang.Boolean,
      inputsHash: Int,
      previousInputsHash: Int,
      valueHashChanged: Boolean,
      serializedPaths: Seq[PathRef]
  )
}
