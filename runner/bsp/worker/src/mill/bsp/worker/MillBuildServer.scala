package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.*
import com.google.gson.JsonObject
import mill.api.*
import mill.api.Segment.Label
import mill.bsp.Constants
import mill.bsp.worker.Utils.{makeBuildTarget, outputPaths, sanitizeUri}
import mill.client.lock.Lock
import mill.api.internal.WatchSig
import mill.internal.PrefixLogger
import mill.server.MillDaemonServer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import mill.api.daemon.internal.bsp.{
  BspModuleApi,
  BspServerResult,
  JvmBuildTarget,
  ScalaBuildTarget
}
import mill.api.daemon.internal.*

import scala.annotation.unused

private class MillBuildServer(
    topLevelProjectRoot: os.Path,
    bspVersion: String,
    serverVersion: String,
    serverName: String,
    canReload: Boolean,
    onShutdown: () => Unit,
    outLock: Lock,
    baseLogger: Logger,
    out: os.Path
) extends BuildServer with AutoCloseable {

  import MillBuildServer._

  def close(): Unit = {
    stopped = true
    evaluatorRequestsThread.interrupt()
  }

  class SessionInfo(
      val clientType: BspClientType,
      val clientWantsSemanticDb: Boolean,
      /** `true` when client and server support the `JvmCompileClasspathProvider` request. */
      val enableJvmCompileClasspathProvider: Boolean
  )

  // Mutable variables representing the lifecycle stages that the MillBuildServer
  // progresses through:
  //
  // Set when the client connects
  protected var client: BuildClient = scala.compiletime.uninitialized
  // Set when the `buildInitialize` message comes in
  protected var sessionInfo: SessionInfo = scala.compiletime.uninitialized
  // Set when the `MillBuildBootstrap` completes and the evaluators are available
  private var bspEvaluators: Promise[BspEvaluators] = Promise[BspEvaluators]()
  private def bspEvaluatorsOpt(): Option[BspEvaluators] =
    bspEvaluators.future.value.flatMap(_.toOption)
  private var savedPreviousEvaluators = Option.empty[BspEvaluators]
  // Set when a session is completed, either due to reload or shutdown
  private[worker] var sessionResult: Promise[BspServerResult] = Promise()

  private val requestCount = new AtomicInteger

  def initialized = sessionInfo != null

  /**
   * Updates the BSP server evaluators.
   *
   * This updates the evaluators used to answer BSP requests. The passed
   * evaluators will be used by the next BSP request to be processed, and
   * those that follow.
   *
   * If errored is true, this method attempts to keep former evaluators
   * for the build / meta-build levels that don't have evaluators anymore.
   * This is useful if the build or some meta-builds don't compile. In that
   * case, we keep the former evaluators for build / meta-build levels that
   * lack a new evaluator, so that we can still answer BSP requests about those,
   * and users can still benefit from IDE features in spite of a broken build.
   *
   * `watched` is used to check whether the evaluators are still valid. If some
   * watched elements changed, the BSP server assumes the evaluators are stale.
   * It completes its result promise with ReloadWorkspace if it picks those
   * changes before the file watcher of the Mill process picks them, in order to
   * signal to the Mill process that it should re-compile the build and update
   * the evaluators.
   *
   * @param evaluators new evaluators
   * @param errored whether the build is errored
   * @param watched files to use to check whether the build is up-to-date or not
   */
  def updateEvaluator(
      evaluators: Seq[EvaluatorApi],
      errored: Boolean,
      watched: Seq[Watchable]
  ): Unit = {
    baseLogger.debug(s"Updating Evaluator: $evaluators")

    // save current evaluators, in case they need to be re-used if `errored` is true,
    // and to know whether this is the first call to `updateEvaluators` or not
    val previousEvaluatorsOpt = bspEvaluatorsOpt().orElse(savedPreviousEvaluators)

    // update the evaluator promise, that we use to pass the evaluators
    // to the thread processing requests
    if (bspEvaluators.isCompleted) bspEvaluators = Promise[BspEvaluators]()

    // actual evaluators that we're going to use: if `errored` is false,
    // the incoming evaluators as is, else a mix of the incoming evaluators
    // and the former ones, for build and meta-build levels that don't compile
    val updatedEvaluators =
      if (errored)
        previousEvaluatorsOpt.map(_.evaluators) match {
          case Some(previous) =>
            evaluators.headOption match {
              case None => previous
              case Some(headEvaluator) =>
                val idx = previous.indexWhere(_.outPathJava == headEvaluator.outPathJava)
                if (idx < 0)
                  // Can't match the former evaluators and the new ones based on their out paths.
                  // Might be a bug. We just discard the former evaluators for now.
                  evaluators
                else
                  previous.take(idx) ++ evaluators
            }
          case None => evaluators
        }
      else
        evaluators

    // update the evaluator promise for the thread processing requests to pick it up
    val evaluators0 = new BspEvaluators(
      topLevelProjectRoot,
      updatedEvaluators,
      s => baseLogger.debug(s()),
      watched
    )
    bspEvaluators.success(evaluators0)

    if (client != null && previousEvaluatorsOpt.nonEmpty) {
      // not the first call to updateEvaluators, and we have a client:
      // compute which targets were added / updated / removed, and
      // send a buildTarget/didChange notification to the client

      val newTargetIds = evaluators0.bspModulesIdList.map {
        case (id, (_, ev)) =>
          id -> ev
      }

      val previousTargetIds = previousEvaluatorsOpt.map(_.bspModulesIdList).getOrElse(Nil).map {
        case (id, (_, ev)) =>
          id -> ev
      }

      val createdAndModifiedEvents = {
        val previousTargetIdsMap = previousTargetIds.toMap
        newTargetIds.flatMap {
          case (id, ev) =>
            previousTargetIdsMap.get(id) match {
              case None =>
                val event = new bsp4j.BuildTargetEvent(id)
                event.setKind(bsp4j.BuildTargetEventKind.CREATED)
                Seq(event)
              case Some(`ev`) =>
                Nil
              case Some(_) =>
                val event = new bsp4j.BuildTargetEvent(id)
                event.setKind(bsp4j.BuildTargetEventKind.CHANGED)
                Seq(event)
            }
        }
      }

      val deletedEvents = {
        val newTargetIdsMap = newTargetIds.toMap
        previousTargetIds.collect {
          case (id, _) if !newTargetIdsMap.contains(id) =>
            val event = new bsp4j.BuildTargetEvent(id)
            event.setKind(bsp4j.BuildTargetEventKind.DELETED)
            event
        }
      }

      val allEvents = deletedEvents ++ createdAndModifiedEvents

      if (allEvents.nonEmpty)
        client.onBuildTargetDidChange(new bsp4j.DidChangeBuildTarget(allEvents.asJava))
    }
  }

  def resetEvaluator(): Unit = {
    baseLogger.debug("Resetting Evaluator")
    savedPreviousEvaluators = bspEvaluatorsOpt().orElse(savedPreviousEvaluators)
    if (bspEvaluators.isCompleted) bspEvaluators = Promise[BspEvaluators]()
  }

  def onConnectWithClient(buildClient: BuildClient): Unit = client = buildClient

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    handlerRaw { logger =>

      val clientCapabilities = request.getCapabilities()
      val enableJvmCompileClasspathProvider = clientCapabilities.getJvmCompileClasspathReceiver
      val clientType = request.getDisplayName match {
        case "IntelliJ-BSP" => BspClientType.IntellijBSP
        case other => BspClientType.Other(other)
      }
      // Not sure why we need to set this early, but we do
      sessionInfo = SessionInfo(
        clientType,
        clientWantsSemanticDb = false,
        enableJvmCompileClasspathProvider = enableJvmCompileClasspathProvider
      )
      // TODO: scan BspModules and infer their capabilities

      val supportedLangs = Constants.languages.asJava
      val capabilities = new BuildServerCapabilities

      capabilities.setBuildTargetChangedProvider(true)
      capabilities.setCanReload(canReload)
      capabilities.setCompileProvider(new CompileProvider(supportedLangs))
      capabilities.setDebugProvider(new DebugProvider(Seq().asJava))
      capabilities.setDependencyModulesProvider(true)
      capabilities.setDependencySourcesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setJvmCompileClasspathProvider(sessionInfo.enableJvmCompileClasspathProvider)
      capabilities.setJvmRunEnvironmentProvider(true)
      capabilities.setJvmTestEnvironmentProvider(true)
      capabilities.setOutputPathsProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setRunProvider(new RunProvider(supportedLangs))
      capabilities.setTestProvider(new TestProvider(supportedLangs))

      def readVersion(json: JsonObject, name: String): Option[String] =
        if (json.has(name)) {
          val rawValue = json.get(name)
          if (rawValue.isJsonPrimitive) {
            val version = Try(rawValue.getAsJsonPrimitive.getAsString).toOption.filter(_.nonEmpty)
            logger.debug(s"Got json value for ${name}=${version}")
            version
          } else None
        } else None

      var clientWantsSemanticDb = false
      request.getData match {
        case d: JsonObject =>
          logger.debug(s"extra data: ${d} of type ${d.getClass}")
          readVersion(d, "semanticdbVersion").foreach { version =>
            logger.info(
              s"Got client semanticdbVersion: ${version}. Enabling SemanticDB support."
            )
            clientWantsSemanticDb = true
            SemanticDbJavaModuleApi.contextSemanticDbVersion.set(Option(version))

            // Inform other BSP clients that we want to use SemanticDB
            val pid = ProcessHandle.current().pid()
            val pidFile = BuildCtx.bspSemanticDbSesssionsFolder / pid.toString
            os.write.over(pidFile, "", createFolders = true)
            pidFile.toNIO.toFile.deleteOnExit()
          }
          readVersion(d, "javaSemanticdbVersion").foreach { version =>
            SemanticDbJavaModuleApi.contextJavaSemanticDbVersion.set(Option(version))
          }
        case _ => // no op
      }

      sessionInfo = SessionInfo(
        clientType,
        clientWantsSemanticDb = clientWantsSemanticDb,
        enableJvmCompileClasspathProvider = enableJvmCompileClasspathProvider
      )
      new InitializeBuildResult(serverName, serverVersion, bspVersion, capabilities)
    }

  override def onBuildInitialized(): Unit = {
    val logger = createLogger()
    logger.info("Build initialized")
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    val logger = createLogger()
    logger.info("Entered buildShutdown")
    SemanticDbJavaModuleApi.resetContext()
    CompletableFuture.completedFuture(null.asInstanceOf[Object])
  }
  override def onBuildExit(): Unit = {
    val logger = createLogger()
    logger.info("Entered onBuildExit")
    SemanticDbJavaModuleApi.resetContext()
    sessionResult.trySuccess(BspServerResult.Shutdown)
    onShutdown()
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    handlerTasksEvaluators(
      targetIds = _.bspModulesIdList.map(_._1),
      tasks = { case m: BspModuleApi => m.bspBuildTargetData },
      requestDescription = "Listing build targets",
      originId = ""
    ) { (ev, state, id, m: BspModuleApi, bspBuildTargetData) =>
      val depsIds = m match {
        case jm: JavaModuleApi =>
          (jm.recursiveModuleDeps ++ jm.compileModuleDepsChecked)
            .distinct
            .collect { case bm: BspModuleApi => state.bspIdByModule(bm) }
        case _ => Seq()
      }
      val data = bspBuildTargetData match {
        case Some((dataKind, d: ScalaBuildTarget)) =>
          val target = new bsp4j.ScalaBuildTarget(
            d.scalaOrganization,
            d.scalaVersion,
            d.scalaBinaryVersion,
            bsp4j.ScalaPlatform.forValue(d.platform.number),
            d.jars.asJava
          )
          for (jvmBuildTarget <- d.jvmBuildTarget)
            target.setJvmBuildTarget(MillBuildServer.jvmBuildTarget(jvmBuildTarget))
          Some((dataKind, target))

        case Some((dataKind, d: JvmBuildTarget)) =>
          Some((dataKind, jvmBuildTarget(d)))

        case Some((dataKind, d)) =>
          ev.baseLogger.debug(s"Unsupported dataKind=${dataKind} with value=${d}")
          None // unsupported data kind
        case None => None
      }

      val bt = m.bspBuildTarget
      makeBuildTarget(id, depsIds, bt, data)

    } { (targets, state) =>
      new WorkspaceBuildTargetsResult(
        (targets.asScala ++ state.syntheticRootBspBuildTarget.map(_.target))
          .sortBy(_.getId.getUri)
          .asJava
      )
    }

  override def workspaceReload(): CompletableFuture[Object] =
    handlerRaw { _ =>
      // Instead stop and restart the command
      // BSP.install(evaluator)
      sessionResult.trySuccess(BspServerResult.ReloadWorkspace)
      ().asInstanceOf[Object]
    }

  /**
   * The build target sources request is sent from the client to the server to query
   * for the list of text documents and directories that are belong to a build
   * target. The sources response must not include sources that are external to the
   * workspace, see [[buildTargetDependencySources()]].
   */
  override def buildTargetSources(sourcesParams: SourcesParams)
      : CompletableFuture[SourcesResult] = {

    def sourceItem(source: os.Path, generated: Boolean) = new SourceItem(
      sanitizeUri(source.toNIO),
      if (source.toIO.isFile) SourceItemKind.FILE else SourceItemKind.DIRECTORY,
      generated
    )

    handlerTasksEvaluators(
      targetIds = _ => sourcesParams.getTargets.asScala,
      tasks = { case module: JavaModuleApi => module.bspJavaModule().bspBuildTargetSources },
      requestDescription =
        s"Getting sources of ${sourcesParams.getTargets.asScala.map(_.getUri).mkString(", ")}",
      originId = ""
    ) {
      case (_, _, id, _, result) => new SourcesItem(
          id,
          (
            result.sources.map(p => sourceItem(os.Path(p), false)) ++
              result.generatedSources.map(p => sourceItem(os.Path(p), true))
          ).asJava
        )
    } { (sourceItems, state) =>
      new SourcesResult(
        (sourceItems.asScala ++ state.syntheticRootBspBuildTarget.map(_.synthSources))
          .sortBy(_.getTarget.getUri)
          .asJava
      )
    }

  }

  override def buildTargetInverseSources(p: InverseSourcesParams)
      : CompletableFuture[InverseSourcesResult] = {
    handlerEvaluators() { (state, logger) =>
      val tasksEvaluators = state.bspModulesIdList.collect {
        case (id, (m: JavaModuleApi, ev)) =>
          (
            result = m.bspJavaModule().bspBuildTargetInverseSources(id, p.getTextDocument.getUri),
            evaluator = ev
          )
      }

      val ids = groupList(tasksEvaluators)(_.evaluator)(_.result)
        .flatMap {
          case (ev, ts) =>
            ev
              .executeApi(
                tasks = ts,
                reporter = Utils.getBspLoggedReporterPool("", state.bspIdByModule, client),
                logger = logger
              )
              .values
              .get
        }
        .flatten

      new InverseSourcesResult(ids.asJava)
    }
  }

  /**
   * External dependencies (sources or source jars).
   *
   * The build target dependency sources request is sent from the client to the
   * server to query for the sources of build target dependencies that are external
   * to the workspace. The dependency sources response must not include source files
   * that belong to a build target within the workspace, see [[buildTargetSources()]].
   *
   * The server communicates during the initialize handshake whether this method is
   * supported or not. This method can for example be used by a language server on
   * `textDocument/definition` to "Go to definition" from project sources to
   * dependency sources.
   */
  override def buildTargetDependencySources(p: DependencySourcesParams)
      : CompletableFuture[DependencySourcesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala,
      tasks = { case m: JavaModuleApi => m.bspJavaModule().bspBuildTargetDependencySources },
      requestDescription =
        s"Getting dependency sources of ${p.getTargets.asScala.map(_.getUri).mkString(", ")}",
      originId = ""
    ) {
      case (_, _, id, _, result) =>
        val cp = (result.resolvedDepsSources ++ result.unmanagedClasspath).map(sanitizeUri)
        new DependencySourcesItem(id, cp.asJava)

    } { values =>
      new DependencySourcesResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  /**
   * External dependencies per module (e.g. ivy deps)
   *
   * The build target dependency modules request is sent from the client to the
   * server to query for the libraries of build target dependencies that are external
   * to the workspace including meta information about library and their sources.
   * It's an extended version of [[buildTargetSources()]].
   */
  override def buildTargetDependencyModules(params: DependencyModulesParams)
      : CompletableFuture[DependencyModulesResult] =
    handlerTasks(
      targetIds = _ => params.getTargets.asScala,
      tasks = { case m: JavaModuleApi => m.bspJavaModule().bspBuildTargetDependencyModules },
      requestDescription = "Getting external dependencies of {}",
      originId = ""
    ) {
      case (_, _, id, _, result) =>
        val deps = result.mvnDeps.collect {
          case (org, repr, version) if org != "mill-internal" =>
            new DependencyModule(repr, version)
        }

        val unmanaged = result.unmanagedClasspath.map { dep =>
          new DependencyModule(s"unmanaged-${dep.getFileName}", "")
        }
        new DependencyModulesItem(id, (deps ++ unmanaged).asJava)

    } { values =>
      new DependencyModulesResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetResources(p: ResourcesParams): CompletableFuture[ResourcesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala,
      tasks = { case m: JavaModuleApi => m.bspJavaModule().bspBuildTargetResources },
      requestDescription = "Getting resources of {}",
      originId = ""
    ) {
      case (_, _, id, _, resources) =>
        val resourcesUrls =
          resources.map(os.Path(_)).filter(os.exists).map(p => sanitizeUri(p.toNIO))
        new ResourcesItem(id, resourcesUrls.asJava)

    } { values =>
      new ResourcesResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] =
    handlerEvaluators() { (state, logger) =>
      p.setTargets(state.filterNonSynthetic(p.getTargets))
      val params = TaskParameters.fromCompileParams(p)
      val compileTasksEvs = params.getTargets.distinct.map(state.bspModulesById).collect {
        case (m: SemanticDbJavaModuleApi, ev) if sessionInfo.clientWantsSemanticDb =>
          ((m, m.bspBuildTargetCompileSemanticDb), ev)
        case (m: JavaModuleApi, ev) => (
            (
              m,
              m.bspBuildTargetCompile(sessionInfo.clientType.mergeResourcesIntoClasses)
            ),
            ev
          )
      }

      val reporterMaker = Utils.getBspLoggedReporterPool(p.getOriginId, state.bspIdByModule, client)
      val reporters = new ConcurrentHashMap[Int, Option[BspCompileProblemReporter]]
      val getReporter: Int => Option[CompileProblemReporter] = { id =>
        if (!reporters.contains(id))
          reporters.putIfAbsent(id, reporterMaker(id))
        reporters.get(id)
      }

      val result = compileTasksEvs
        .groupMap(_._2)(_._1)
        .map { case (ev, ts) =>
          evaluate(
            ev,
            s"Compiling ${ts.map(_._1.bspDisplayName).mkString(", ")}",
            ts.map(_._2),
            logger,
            getReporter,
            TestReporter.DummyTestReporter,
            errorOpt = { result =>
              val baseErrorOpt = evaluatorErrorOpt(result)
              def hasCompilationErrors =
                reporters.asScala.valuesIterator.flatMap(_.iterator).exists(_.hasErrors)
              if (baseErrorOpt.isEmpty || hasCompilationErrors)
                // No task errors, or some compilation errors were already reported:
                // no need to tell more about this to users
                None
              else
                // No compilation errors were reported: report task errors if any
                baseErrorOpt
            }
          )
        }
        .toSeq
      val compileResult = new CompileResult(Utils.getStatusCode(result))
      compileResult.setOriginId(p.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetOutputPaths(params: OutputPathsParams)
      : CompletableFuture[OutputPathsResult] =
    handlerEvaluators() { (state, _) =>
      val synthOutpaths = for {
        synthTarget <- state.syntheticRootBspBuildTarget
        if params.getTargets.contains(synthTarget.id)
        baseDir <- synthTarget.bt.baseDirectory
      } yield new OutputPathsItem(
        synthTarget.id,
        outputPaths(os.Path(baseDir), topLevelProjectRoot).asJava
      )

      val items = for {
        target <- params.getTargets.asScala
        (module, _) <- state.bspModulesById.get(target)
      } yield {
        val items = outputPaths(
          os.Path(module.bspBuildTarget.baseDirectory.get),
          topLevelProjectRoot
        )

        new OutputPathsItem(target, items.asJava)
      }

      new OutputPathsResult((items ++ synthOutpaths).sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    handlerEvaluators() { (state, logger) =>
      val params = TaskParameters.fromRunParams(runParams)
      val (runModule, ev) = params.getTargets.map(state.bspModulesById).collectFirst {
        case (m: RunModuleApi, ev) => (m, ev)
      }.get

      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = runModule.bspRunModule().bspRun(args)
      val runResult = evaluate(
        ev,
        s"Running ${runModule.bspDisplayName}",
        Seq(runTask),
        logger,
        Utils.getBspLoggedReporterPool(runParams.getOriginId, state.bspIdByModule, client)
      )
      val response = runResult.transitiveResultsApi(runTask) match {
        case r if r.asSuccess.isDefined => new RunResult(StatusCode.OK)
        case _ => new RunResult(StatusCode.ERROR)
      }
      params.getOriginId match {
        case Some(id) => response.setOriginId(id)
        case None =>
      }

      response
    }

  override def buildTargetTest(testParams: TestParams): CompletableFuture[TestResult] =
    handlerEvaluators() { (state, logger) =>
      testParams.setTargets(state.filterNonSynthetic(testParams.getTargets))
      val millBuildTargetIds = state
        .rootModules
        .map { case m: BspModuleApi => state.bspIdByModule(m) }
        .toSet

      val params = TaskParameters.fromTestParams(testParams)
      val argsMap =
        try {
          val scalaTestParams = testParams.getData.asInstanceOf[JsonObject]
          (for (testItem <- scalaTestParams.get("testClasses").getAsJsonArray.asScala)
            yield (
              testItem.getAsJsonObject.get("target").getAsJsonObject.get("uri").getAsString,
              testItem.getAsJsonObject.get("classes").getAsJsonArray.asScala.map(elem =>
                elem.getAsString
              ).toSeq
            )).toMap
        } catch {
          case _: Exception =>
            (for (targetId <- testParams.getTargets.asScala)
              yield (targetId.getUri, Seq.empty[String])).toMap
        }

      val overallStatusCode = testParams.getTargets.asScala
        .filter(millBuildTargetIds.contains)
        .foldLeft(StatusCode.OK) { (overallStatusCode, targetId) =>
          state.bspModulesById(targetId) match {
            case (testModule: TestModuleApi, ev) =>
              val testTask = testModule.testLocal(argsMap(targetId.getUri)*)

              // notifying the client that the testing of this build target started
              val taskStartParams = new TaskStartParams(new TaskId(testTask.hashCode().toString))
              taskStartParams.setEventTime(System.currentTimeMillis())
              taskStartParams.setMessage("Testing target: " + targetId)
              taskStartParams.setDataKind(TaskStartDataKind.TEST_TASK)
              taskStartParams.setData(new TestTask(targetId))
              client.onBuildTaskStart(taskStartParams)

              val testReporter =
                new BspTestReporter(
                  client,
                  targetId,
                  new TaskId(testTask.hashCode().toString)
                )

              val results = evaluate(
                ev,
                s"Running tests for ${testModule.bspDisplayName}",
                Seq(testTask),
                logger,
                reporter = Utils.getBspLoggedReporterPool(
                  testParams.getOriginId,
                  state.bspIdByModule,
                  client
                ),
                testReporter
              )
              val statusCode = Utils.getStatusCode(Seq(results))

              // Notifying the client that the testing of this build target ended
              val taskFinishParams =
                new TaskFinishParams(new TaskId(testTask.hashCode().toString), statusCode)
              taskFinishParams.setEventTime(System.currentTimeMillis())
              taskFinishParams.setMessage(
                s"Finished testing target${testModule.bspBuildTarget.displayName}"
              )
              taskFinishParams.setDataKind(TaskFinishDataKind.TEST_REPORT)
              taskFinishParams.setData(testReporter.getTestReport)
              client.onBuildTaskFinish(taskFinishParams)

              (statusCode, overallStatusCode) match {
                case (StatusCode.ERROR, _) | (_, StatusCode.ERROR) => StatusCode.ERROR
                case (StatusCode.CANCELLED, _) => StatusCode.CANCELLED
                case (StatusCode.OK, _) => StatusCode.OK
              }

            case _ => overallStatusCode
          }
        }

      val testResult = new TestResult(overallStatusCode)
      params.getOriginId match {
        case None => testResult
        case Some(id) =>
          // TODO: Add the messages from mill to the data field?
          testResult.setOriginId(id)
          testResult
      }
    }

  override def buildTargetCleanCache(cleanCacheParams: CleanCacheParams)
      : CompletableFuture[CleanCacheResult] =
    handlerEvaluators() { (state, logger) =>
      cleanCacheParams.setTargets(state.filterNonSynthetic(cleanCacheParams.getTargets))

      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.foldLeft((
          "",
          true
        )) {
          case ((msg, cleaned), targetId) =>
            val (module, ev) = state.bspModulesById(targetId)
            val mainModule = ev.rootModule.asInstanceOf[mill.api.daemon.internal.MainModuleApi]
            val compileTaskName = (module.moduleSegments ++ Label("compile")).render
            logger.debug(s"about to clean: ${compileTaskName}")
            val cleanTask = mainModule.bspMainModule().bspClean(ev, Seq(compileTaskName)*)
            val cleanResult = evaluate(
              ev,
              s"Cleaning cache of ${module.bspDisplayName}",
              Seq(cleanTask),
              logger = logger,
              reporter = Utils.getBspLoggedReporterPool("", state.bspIdByModule, client)
            )
            val cleanedPaths =
              cleanResult.results.head.get.value.asInstanceOf[Seq[java.nio.file.Path]]
            if (cleanResult.transitiveFailingApi.size > 0) (
              msg + s" Target ${compileTaskName} could not be cleaned. See message from mill: \n" +
                (cleanResult.transitiveResultsApi(cleanTask) match {
                  case ex: ExecResult.Exception => ex.toString()
                  case ExecResult.Skipped => "Task was skipped"
                  case ExecResult.Aborted => "Task was aborted"
                  case _ => "could not retrieve the failure message"
                }),
              false
            )
            else {
              while (cleanedPaths.exists(p => os.exists(os.Path(p)))) Thread.sleep(10)

              (msg + s"${module.bspBuildTarget.displayName} cleaned \n", cleaned)
            }
        }

      new CleanCacheResult(cleaned).tap { it =>
        it.setMessage(msg)
      }
    }

  override def debugSessionStart(debugParams: DebugSessionParams)
      : CompletableFuture[DebugSessionAddress] =
    handlerEvaluators() { (state, _) =>
      debugParams.setTargets(state.filterNonSynthetic(debugParams.getTargets))
      throw new NotImplementedError("debugSessionStart endpoint is not implemented")
    }

  /**
   * @params tasks A partial function
   * @param block The function must accept the same modules as the partial function given by `tasks`.
   */
  def handlerTasks[T, V, W](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String,
      originId: String
  )(block: (
      evaluator: EvaluatorApi,
      bspEvaluators: BspEvaluators,
      buildTargetIdentifier: BuildTargetIdentifier,
      moduleApi: BspModuleApi,
      result: W
  ) => T)(agg: java.util.List[T] => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  )
      : CompletableFuture[V] =
    handlerTasksEvaluators[T, V, W](targetIds, tasks, requestDescription, originId)(block)((l, _) =>
      agg(l)
    )(using name, enclosing)

  /**
   * @params tasks A partial function
   * @param block The function must accept the same modules as the partial function given by `tasks`.
   */
  def handlerTasksEvaluators[T, V, W](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String,
      originId: String
  )(block: (EvaluatorApi, BspEvaluators, BuildTargetIdentifier, BspModuleApi, W) => T)(agg: (
      java.util.List[T],
      BspEvaluators
  ) => V)(using name: sourcecode.Name, enclosing: sourcecode.Enclosing): CompletableFuture[V] = {
    val prefix = name.value
    handlerEvaluators() { (state, logger) =>
      val ids = state.filterNonSynthetic(targetIds(state).asJava).asScala
      val tasksSeq = ids.flatMap { id =>
        val (m, ev) = state.bspModulesById(id)
        tasks.lift.apply(m).map(ts => (ts, (ev, id, m)))
      }

      // group by evaluator (different root module)
      val groups0 = groupList(tasksSeq)(_._2._1) {
        case (tasks, (_, id, m)) => (id, m, tasks)
      }

      val evaluated = groups0.flatMap {
        case (ev, targetIdTasks) =>
          val requestDescription0 = requestDescription.replace(
            "{}",
            targetIdTasks.map(_._2.bspDisplayName).mkString(", ")
          )
          val results = evaluate(
            ev,
            requestDescription0,
            targetIdTasks.map(_._3),
            logger = logger,
            reporter = Utils.getBspLoggedReporterPool(originId, state.bspIdByModule, client)
          )
          val resultsById = targetIdTasks.flatMap {
            case (id, m, task) =>
              results.transitiveResultsApi(task)
                .asSuccess
                .map(_.value.value.asInstanceOf[W])
                .map((id, m, _))
          }

          def logError(id: BuildTargetIdentifier, errorMsg: String): Unit = {
            val msg = s"Request '$prefix' failed for ${id.getUri}: ${errorMsg}"
            logger.error(msg)
            client.onBuildLogMessage(new LogMessageParams(MessageType.ERROR, msg))
          }

          resultsById.flatMap {
            case (id, m, values) =>
              try Seq(block(ev, state, id, m, values))
              catch {
                case NonFatal(e) =>
                  logError(id, e.toString)
                  Seq()
              }
          }
      }

      agg(evaluated.asJava, state)
    }
  }

  private val queue = new LinkedBlockingQueue[(BspEvaluators => Unit, Logger, String)]
  private var stopped = false
  private val evaluatorRequestsThread: Thread =
    new Thread("mill-bsp-evaluator") {
      setDaemon(true)
      def waitForEvaluators(): Unit =
        Await.result(bspEvaluators.future, Duration.Inf)
      override def run(): Unit =
        try {
          var elemOpt = Option.empty[(BspEvaluators => Unit, Logger, String)]
          while (!stopped) {
            if (elemOpt.isEmpty)
              elemOpt = Option(queue.poll(1L, TimeUnit.SECONDS))
            for ((block, logger, name) <- elemOpt) {
              waitForEvaluators()
              MillDaemonServer.withOutLock(
                noBuildLock = false,
                noWaitForBuildLock = false,
                out = out,
                millActiveCommandMessage = s"IDE:$name",
                streams = logger.streams,
                outLock = outLock
              ) {
                for (evaluator <- bspEvaluatorsOpt())
                  if (evaluator.watched.forall(WatchSig.haveNotChanged)) {
                    elemOpt = None
                    try block(evaluator)
                    catch {
                      case t: Throwable =>
                        logger.error(s"Could not process request: $t")
                        t.printStackTrace(logger.streams.err)
                    }
                  } else {
                    resetEvaluator()
                    sessionResult.trySuccess(BspServerResult.ReloadWorkspace)
                  }
              }
            }
          }
        } catch {
          case _: InterruptedException =>
          // ignored, normal exit
        }
    }

  evaluatorRequestsThread.start()

  protected def handlerEvaluators[V](
      checkInitialized: Boolean = true
  )(block: (BspEvaluators, Logger) => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val prefix = name.value
    val logger = createLogger()

    val future = new CompletableFuture[V]

    if (checkInitialized && !initialized) {
      val msg = s"Can not respond to $prefix request before receiving the `initialize` request."
      logger.error(msg)
      future.completeExceptionally(new Exception(msg))
    } else {
      val proceed: BspEvaluators => Unit = { evaluators =>
        if (future.isCancelled())
          logger.info(s"$prefix was cancelled")
        else {

          val start = System.currentTimeMillis()
          logger.info(s"Entered $prefix")

          val res =
            try Success(block(evaluators, logger))
            catch {
              case t: Throwable => Failure(t)
            }

          logger.info(s"$prefix took ${System.currentTimeMillis() - start} msec")

          res match {
            case Success(v) =>
              logger.debug(s"$prefix result: $v")
              future.complete(v)
            case Failure(err) =>
              logger.error(s"$prefix caught exception: $err")
              err.printStackTrace(logger.streams.err)
              future.completeExceptionally(err)
          }
        }
      }
      queue.put((proceed, logger, name.value))
    }

    future
  }

  protected def handlerRaw[V](block: Logger => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val logger = createLogger()
    val start = System.currentTimeMillis()
    val prefix = name.value
    logger.info(s"Entered $prefix")
    def logTiming() =
      logger.info(s"$prefix took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]
    try {
      val v = block(logger)
      logTiming()
      logger.debug(s"$prefix result: $v")
      future.complete(v)
    } catch {
      case e: Exception =>
        logTiming()
        logger.error(s"$prefix caught exception: $e")
        e.printStackTrace(logger.streams.err)
        future.completeExceptionally(e)
    }

    future
  }

  override def onRunReadStdin(params: ReadParams): Unit = {
    val logger = createLogger()
    logger.debug("onRunReadStdin is current unsupported")
  }

  protected def createLogger()(using enclosing: sourcecode.Enclosing): Logger = {
    val requestCount0 = requestCount.incrementAndGet()
    val name = enclosingRequestName
    new MillBspLogger(
      client,
      requestCount0,
      new PrefixLogger(
        new ProxyLogger(baseLogger) {
          override private[mill] def logKey: Seq[String] = {
            val logKey0 = super.logKey
            if (logKey0.startsWith(Seq("bsp"))) logKey0.drop(1)
            else logKey0
          }
        },
        Seq(requestCount0.toString, name)
      )
    )
  }

  private def evaluatorErrorOpt(result: EvaluatorApi.Result[Any]): Option[String] =
    result.values.toEither.left.toOption

  private def evaluate(
      evaluator: EvaluatorApi,
      @unused requestDescription: String,
      goals: Seq[TaskApi[?]],
      logger: Logger,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      errorOpt: EvaluatorApi.Result[Any] => Option[String] = evaluatorErrorOpt
  ): ExecutionResultsApi = {
    val goalCount = goals.length
    logger.info(s"Evaluating $goalCount ${if (goalCount > 1) "tasks" else "task"}")
    val result = evaluator.executeApi(
      goals,
      reporter,
      testReporter,
      logger,
      serialCommandExec = false
    )
    errorOpt(result) match {
      case None =>
        logger.info("Done")
      case Some(error) =>
        logger.warn(error)
        logger.info("Failed")
        client.onBuildLogMessage(new LogMessageParams(MessageType.WARNING, error))
    }
    result.executionResults
  }

  @JsonRequest("millTest/loggingTest")
  def loggingTest(): CompletableFuture[Object] = {
    handlerEvaluators() { (state, logger) =>
      val tasksEvs = state.bspModulesIdList
        .collectFirst {
          case (_, (m: JavaModuleApi, ev)) =>
            Seq(((m, m.bspJavaModule().bspLoggingTest), ev))
        }
        .getOrElse {
          sys.error("No BSP build target available")
        }

      tasksEvs
        .groupMap(_._2)(_._1)
        .map { case (ev, ts) =>
          evaluate(
            ev,
            s"Checking logging for ${ts.map(_._1.bspDisplayName).mkString(", ")}",
            ts.map(_._2),
            logger,
            reporter = Utils.getBspLoggedReporterPool("", state.bspIdByModule, client)
          )
        }
        .toSeq
      null
    }
  }

}

private object MillBuildServer {

  /**
   * Same as Iterable.groupMap, but returns a sequence instead of a map, and preserves
   * the order of appearance of the keys from the input sequence
   */
  private def groupList[A, K, B](seq: collection.Seq[A])(key: A => K)(f: A => B)
      : Seq[(K, Seq[B])] = {
    val map = new mutable.HashMap[K, mutable.ListBuffer[B]]
    val list = new mutable.ListBuffer[(K, mutable.ListBuffer[B])]
    for (a <- seq) {
      val k = key(a)
      val b = f(a)
      val l = map.getOrElseUpdate(
        k, {
          val buf = mutable.ListBuffer[B]()
          list.append((k, buf))
          buf
        }
      )
      l.append(b)
    }
    list
      .iterator
      .map { case (k, l) => (k, l.result()) }
      .toList
  }

  def jvmBuildTarget(d: JvmBuildTarget): bsp4j.JvmBuildTarget =
    new bsp4j.JvmBuildTarget().tap { it =>
      d.javaHome.foreach(jh => it.setJavaHome(jh.uri))
      d.javaVersion.foreach(jv => it.setJavaVersion(jv))
    }

  private[mill] def enclosingRequestName(using enclosing: sourcecode.Enclosing): String = {
    // enclosing.value typically looks like "mill.bsp.worker.MillBuildServer#buildTargetCompile logger"
    // First, try to isolate the part with the BSP request name
    var name0 = enclosing.value.split(" ") match {
      case Array(elem) => elem
      case other => other(other.length - 2)
    }

    // In "mill.bsp.worker.MillBuildServer#buildTargetCompile", keep only "buildTargetCompile"
    val sharpIdx = name0.lastIndexOf('#')
    if (sharpIdx > 0)
      name0 = name0.drop(sharpIdx + 1)

    // Drop "buildTarget" from "buildTargetCompile",
    // and change the resulting "Compile" to "compile"
    if (name0.startsWith("buildTarget")) {
      val stripped = name0.stripPrefix("buildTarget")
      if (stripped.headOption.exists(_.isUpper))
        name0 = stripped.head.toLower +: stripped.tail
    }
    name0
  }
}
