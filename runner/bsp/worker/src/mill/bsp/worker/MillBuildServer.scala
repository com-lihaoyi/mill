package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.*
import com.google.gson.JsonObject
import mill.api.*
import mill.api.internal.{JvmBuildTarget, ScalaBuildTarget, *}
import mill.api.Segment.Label
import mill.bsp.Constants
import mill.bsp.worker.Utils.{makeBuildTarget, outputPaths, sanitizeUri}
import mill.client.lock.Lock
import mill.server.Server
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import mill.internal.PrefixLogger

private class MillBuildServer(
    topLevelProjectRoot: os.Path,
    bspVersion: String,
    serverVersion: String,
    serverName: String,
    logStream: PrintStream,
    canReload: Boolean,
    debugMessages: Boolean,
    onShutdown: () => Unit,
    outLock: Lock,
    baseLogger: Logger
)(implicit ec: scala.concurrent.ExecutionContext) extends BuildServer {

  import MillBuildServer._

  class SessionInfo(
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
  // Set when a session is completed, either due to reload or shutdown
  private[worker] var sessionResult: Option[BspServerResult] = None

  private val requestCount = new AtomicInteger

  def initialized = sessionInfo != null

  def updateEvaluator(evaluatorsOpt: Option[Seq[EvaluatorApi]]): Unit = {
    baseLogger.debug(s"Updating Evaluator: $evaluatorsOpt")
    if (bspEvaluators.isCompleted) bspEvaluators = Promise[BspEvaluators]() // replace the promise
    evaluatorsOpt.foreach { evaluators =>
      bspEvaluators.success(new BspEvaluators(
        topLevelProjectRoot,
        evaluators,
        s => baseLogger.debug(s())
      ))
    }
  }

  def onConnectWithClient(buildClient: BuildClient): Unit = client = buildClient

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    handlerRaw(checkInitialized = false) { logger =>

      val clientCapabilities = request.getCapabilities()
      val enableJvmCompileClasspathProvider = clientCapabilities.getJvmCompileClasspathReceiver
      // Not sure why we need to set this early, but we do
      sessionInfo = SessionInfo(false, enableJvmCompileClasspathProvider)
      // TODO: scan BspModules and infer their capabilities

      val supportedLangs = Constants.languages.asJava
      val capabilities = new BuildServerCapabilities

      capabilities.setBuildTargetChangedProvider(false)
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
          }
          readVersion(d, "javaSemanticdbVersion").foreach { version =>
            SemanticDbJavaModuleApi.contextJavaSemanticDbVersion.set(Option(version))
          }
        case _ => // no op
      }

      sessionInfo = SessionInfo(clientWantsSemanticDb, enableJvmCompileClasspathProvider)
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
    sessionResult = Some(BspServerResult.Shutdown)
    onShutdown()
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    handlerTasksEvaluators(
      targetIds = _.bspModulesIdList.map(_._1),
      tasks = { case m: BspModuleApi => m.bspBuildTargetData },
      requestDescription = "Listing build targets"
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
    handlerRaw(checkInitialized = false) { _ =>
      // Instead stop and restart the command
      // BSP.install(evaluator)
      sessionResult = Some(BspServerResult.ReloadWorkspace)
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
      tasks = { case module: JavaModuleApi => module.bspBuildTargetSources },
      requestDescription =
        s"Getting sources of ${sourcesParams.getTargets.asScala.map(_.getUri).mkString(", ")}"
    ) {
      case (ev, state, id, module, items) => new SourcesItem(
          id,
          (items._1.map(p => sourceItem(os.Path(p), false)) ++ items._2.map(p =>
            sourceItem(os.Path(p), true)
          )).asJava
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
      : CompletableFuture[InverseSourcesResult] =
    handlerEvaluators() { (state, _) =>
      val tasksEvaluators = state.bspModulesIdList.collect {
        case (id, (m: JavaModuleApi, ev)) =>
          m.bspBuildTargetInverseSources(id, p.getTextDocument.getUri) -> ev
      }

      val ids = groupList(tasksEvaluators)(_._2)(_._1)
        .flatMap { case (ev, ts) => ev.executeApi(ts).values.get }
        .flatten

      new InverseSourcesResult(ids.asJava)
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
      tasks = { case m: JavaModuleApi => m.bspBuildTargetDependencySources },
      requestDescription =
        s"Getting dependency sources of ${p.getTargets.asScala.map(_.getUri).mkString(", ")}"
    ) {
      case (
            ev,
            state,
            id,
            m: JavaModuleApi,
            (resolveDepsSources, unmanagedClasspath)
          ) =>
        val cp = (resolveDepsSources ++ unmanagedClasspath).map(sanitizeUri)
        new DependencySourcesItem(id, cp.asJava)
      case _ => ???
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
      tasks = { case m: JavaModuleApi => m.bspBuildTargetDependencyModules },
      requestDescription = "Getting external dependencies of {}"
    ) {
      case (
            ev,
            state,
            id,
            m: JavaModuleApi,
            (mvnDeps, unmanagedClasspath)
          ) =>
        val deps = mvnDeps.collect {
          case (org, repr, version) if org != "mill-internal" =>
            new DependencyModule(repr, version)
        }

        val unmanaged = unmanagedClasspath.map { dep =>
          new DependencyModule(s"unmanaged-${dep.getFileName}", "")
        }
        new DependencyModulesItem(id, (deps ++ unmanaged).asJava)
      case _ => ???
    } { values =>
      new DependencyModulesResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetResources(p: ResourcesParams): CompletableFuture[ResourcesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala,
      tasks = { case m: JavaModuleApi => m.bspBuildTargetResources },
      requestDescription = "Getting resources of {}"
    ) {
      case (ev, state, id, m, resources) =>
        val resourcesUrls =
          resources.map(os.Path(_)).filter(os.exists).map(p => sanitizeUri(p.toNIO))
        new ResourcesItem(id, resourcesUrls.asJava)

    } { values =>
      new ResourcesResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] = {
    handlerEvaluators() { (state, logger) =>
      p.setTargets(state.filterNonSynthetic(p.getTargets))
      val params = TaskParameters.fromCompileParams(p)
      val compileTasksEvs = params.getTargets.distinct.map(state.bspModulesById).collect {
        case (m: SemanticDbJavaModuleApi, ev) if sessionInfo.clientWantsSemanticDb =>
          ((m, m.bspBuildTargetCompileSemanticDb), ev)
        case (m: JavaModuleApi, ev) => ((m, m.bspBuildTargetCompile), ev)
      }

      val result = compileTasksEvs
        .groupMap(_._2)(_._1)
        .map { case (ev, ts) =>
          evaluate(
            ev,
            s"Compiling ${ts.map(_._1.bspDisplayName).mkString(", ")}",
            ts.map(_._2),
            logger,
            Utils.getBspLoggedReporterPool(p.getOriginId, state.bspIdByModule, client),
            TestReporter.DummyTestReporter
          )
        }
        .toSeq
      val compileResult = new CompileResult(Utils.getStatusCode(result))
      compileResult.setOriginId(p.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }
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
        (module, ev) <- state.bspModulesById.get(target)
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
      val (module, ev) = params.getTargets.map(state.bspModulesById).collectFirst {
        case (m: JavaModuleApi, ev) => (m, ev)
      }.get

      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.bspRun(args)
      val runResult = evaluate(
        ev,
        s"Running ${module.bspDisplayName}",
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
                Utils.getBspLoggedReporterPool(
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
            val mainModule = ev.rootModule.asInstanceOf[mill.api.internal.MainModuleApi]
            val compileTargetName = (module.moduleSegments ++ Label("compile")).render
            logger.debug(s"about to clean: ${compileTargetName}")
            val cleanTask = mainModule.bspClean(ev, Seq(compileTargetName)*)
            val cleanResult = evaluate(
              ev,
              s"Cleaning cache of ${module.bspDisplayName}",
              Seq(cleanTask),
              logger = logger
            )
            val cleanedPaths =
              cleanResult.results.head.get.value.asInstanceOf[Seq[java.nio.file.Path]]
            if (cleanResult.transitiveFailingApi.size > 0) (
              msg + s" Target ${compileTargetName} could not be cleaned. See message from mill: \n" +
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
  def handlerTasks[T, V, W: ClassTag](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String
  )(block: (
      EvaluatorApi,
      BspEvaluators,
      BuildTargetIdentifier,
      BspModuleApi,
      W
  ) => T)(agg: java.util.List[T] => V)(implicit
      name: sourcecode.Name
  )
      : CompletableFuture[V] =
    handlerTasksEvaluators[T, V, W](targetIds, tasks, requestDescription)(block)((l, _) =>
      agg(l)
    )

  /**
   * @params tasks A partial function
   * @param block The function must accept the same modules as the partial function given by `tasks`.
   */
  def handlerTasksEvaluators[T, V, W: ClassTag](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String
  )(block: (EvaluatorApi, BspEvaluators, BuildTargetIdentifier, BspModuleApi, W) => T)(agg: (
      java.util.List[T],
      BspEvaluators
  ) => V)(implicit name: sourcecode.Name, enclosing: sourcecode.Enclosing): CompletableFuture[V] = {
    val prefix = name.value
    handlerEvaluators() { (state, logger) =>
      val ids = state.filterNonSynthetic(targetIds(state).asJava).asScala
      val tasksSeq = ids.flatMap { id =>
        val (m, ev) = state.bspModulesById(id)
        tasks.lift.apply(m).map(ts => (ts, (ev, id, m.bspDisplayName)))
      }

      // group by evaluator (different root module)
      val groups0 = groupList(tasksSeq)(_._2._1) {
        case (tasks, (_, id, displayName)) => (id, displayName, tasks)
      }

      val evaluated = groups0.flatMap {
        case (ev, targetIdTasks) =>
          val requestDescription0 = requestDescription.replace(
            "{}",
            targetIdTasks.map(_._2).mkString(", ")
          )
          val results = evaluate(ev, requestDescription0, targetIdTasks.map(_._3), logger = logger)
          val resultsById = targetIdTasks.flatMap {
            case (id, _, task) =>
              results.transitiveResultsApi(task)
                .asSuccess
                .map(_.value.value.asInstanceOf[W])
                .map((id, _))
          }

          def logError(id: BuildTargetIdentifier, errorMsg: String): Unit = {
            val msg = s"Request '$prefix' failed for ${id.getUri}: ${errorMsg}"
            logger.error(msg)
            client.onBuildLogMessage(new LogMessageParams(MessageType.ERROR, msg))
          }

          resultsById.flatMap {
            case (id, values) =>
              try Seq(block(ev, state, id, state.bspModulesById(id)._1, values))
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

  val requestLock = new java.util.concurrent.locks.ReentrantLock()

  protected def handlerEvaluators[V](
      checkInitialized: Boolean = true
  )(block: (BspEvaluators, Logger) => V)(implicit
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val logger = createLogger()
    handler0[BspEvaluators, V](
      logger,
      checkInitialized,
      bspEvaluators.future
    )(block(_, logger))(name)
  }

  protected def handlerRaw[V](
      checkInitialized: Boolean = true
  )(block: Logger => V)(implicit
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V] = {
    val logger = createLogger()
    handler0[Unit, V](
      logger,
      checkInitialized,
      scala.concurrent.Future.successful(())
    )(_ => block(logger))(name)
  }

  /**
   * Given a function that take input of type T and return output of type V,
   * apply the function on the given inputs and return a completable future of
   * the result. If the execution of the function raises an Exception, complete
   * the future exceptionally. Also complete exceptionally if the server was not
   * yet initialized.
   */
  protected def handler0[T, V](
      logger: Logger,
      checkInitialized: Boolean,
      future0: scala.concurrent.Future[T]
  )(block: T => V)(implicit name: sourcecode.Name): CompletableFuture[V] = {

    val start = System.currentTimeMillis()
    val prefix = name.value
    logger.info(s"Entered ${prefix}")
    def logTiming() =
      logger.info(s"${prefix} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()
    if (checkInitialized && !initialized) {
      val msg = s"Can not respond to ${prefix} request before receiving the `initialize` request."
      logger.error(msg)
      future.completeExceptionally(
        new Exception(msg)
      )
    } else {
      future0.onComplete {
        case Success(state) =>
          try {
            requestLock.lock()
            val v = block(state)
            logTiming()
            logger.debug(s"${prefix} result: ${v}")
            future.complete(v)
          } catch {
            case e: Exception =>
              logTiming()
              logger.error(s"${prefix} caught exception: ${e}")
              e.printStackTrace(logger.streams.err)
              future.completeExceptionally(e)
          } finally {
            requestLock.unlock()
          }
        case Failure(exception) =>
          future.completeExceptionally(exception)
      }
    }

    future
  }

  override def onRunReadStdin(params: ReadParams): Unit = {
    val logger = createLogger()
    logger.debug("onRunReadStdin is current unsupported")
  }

  protected def createLogger()(implicit enclosing: sourcecode.Enclosing): Logger = {
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

  private def evaluate(
      evaluator: EvaluatorApi,
      requestDescription: String,
      goals: Seq[TaskApi[?]],
      logger: Logger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter
  )(implicit name: sourcecode.Name): ExecutionResultsApi = {
    val logger0 = Option(logger).getOrElse(evaluator.baseLogger)
    Server.withOutLock(
      noBuildLock = false,
      noWaitForBuildLock = false,
      out = os.Path(evaluator.outPathJava),
      millActiveCommandMessage = "IDE-BSP:" + name.value,
      streams = logger0.streams,
      outLock = outLock
    ) {
      val goalCount = goals.length
      logger.info(s"Evaluating $goalCount ${if (goalCount > 1) "tasks" else "task"}")
      val result =
        mill.api.ClassLoader.withContextClassLoader(evaluator.rootModule.getClass.getClassLoader) {
          evaluator.executeApi(
            goals,
            reporter,
            testReporter,
            logger,
            serialCommandExec = false
          )
        }
      result.values.toEither.left.toOption match {
        case None =>
          logger.info("Done")
        case Some(error) =>
          logger.error(error)
          logger.info("Failed")
          client.onBuildLogMessage(new LogMessageParams(MessageType.ERROR, error))
          client.onBuildShowMessage(new ShowMessageParams(
            MessageType.ERROR,
            s"$requestDescription failed, see Mill logs for more details"
          ))
      }
      result.executionResults
    }
  }

  @JsonRequest("millTest/loggingTest")
  def loggingTest(): CompletableFuture[Object] = {
    handlerEvaluators() { (state, logger) =>
      val tasksEvs = state.bspModulesIdList
        .collectFirst {
          case (_, (m: JavaModuleApi, ev)) =>
            Seq(((m, m.bspLoggingTest), ev))
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
            logger
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

  private[mill] def enclosingRequestName(implicit enclosing: sourcecode.Enclosing): String = {
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
