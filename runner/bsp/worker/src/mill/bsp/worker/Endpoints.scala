package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.*
import com.google.gson.JsonObject
import mill.api.*
import mill.api.Segment.Label
import mill.bsp.Constants
import mill.bsp.worker.Utils.{
  combineStatusCodes,
  jvmBuildTarget,
  makeBuildTarget,
  outputPaths,
  sanitizeUri
}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import mill.api.daemon.internal.bsp.{
  BspModuleApi,
  BspServerResult,
  JvmBuildTarget,
  ScalaBuildTarget
}
import mill.api.daemon.internal.*

/**
 * Contains all BSP protocol endpoint implementations.
 * This trait is mixed into MillBuildServer to separate the API endpoints
 * from the server infrastructure code.
 */
trait MillBspEndpoints extends BuildServer with EndpointsApi {

  // ==========================================================================
  // Abstract members provided by MillBuildServer
  // ==========================================================================

  // ==========================================================================
  // BSP Protocol - Lifecycle
  // ==========================================================================

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    handlerRaw { logger =>

      val clientCapabilities = request.getCapabilities()
      val enableJvmCompileClasspathProvider =
        Option(clientCapabilities.getJvmCompileClasspathReceiver).exists(_.booleanValue())
      val clientType = request.getDisplayName match {
        case "IntelliJ-BSP" => ClientType.IntellijBSP
        case other => ClientType.Other(other)
      }

      val supportedLangs = Constants.languages.asJava
      val capabilities = new BuildServerCapabilities

      capabilities.setBuildTargetChangedProvider(true)
      capabilities.setCanReload(canReload)
      capabilities.setCompileProvider(new CompileProvider(supportedLangs))
      capabilities.setDebugProvider(new DebugProvider(Seq().asJava))
      capabilities.setDependencyModulesProvider(true)
      capabilities.setDependencySourcesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setJvmCompileClasspathProvider(enableJvmCompileClasspathProvider)
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

      sessionInfo = new MillBspEndpoints.SessionInfo(
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

  // ==========================================================================
  // BSP Protocol - Build Target Queries
  // ==========================================================================

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
          for (jbt <- d.jvmBuildTarget)
            target.setJvmBuildTarget(jvmBuildTarget(jbt))
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

      val ids = Utils.groupList(tasksEvaluators)(_.evaluator)(_.result)
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

  // ==========================================================================
  // BSP Protocol - Build Target Operations
  // ==========================================================================

  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] =
    handlerEvaluators() { (state, logger) =>
      p.setTargets(state.filterNonSynthetic(p.getTargets))
      val compileTasksEvs = p.getTargets.asScala.distinct.map(state.bspModulesById).collect {
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
      val reporters =
        new java.util.concurrent.ConcurrentHashMap[Int, Option[BspCompileProblemReporter]]
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
            ts.map(_._2).toSeq,
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
      val (runModule, ev) = Seq(runParams.getTarget).map(state.bspModulesById).collectFirst {
        case (m: RunModuleApi, ev) => (m, ev)
      }.get

      val args = Option(runParams.getArguments).map(_.asScala.toSeq).getOrElse(Seq.empty[String])
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
      Option(runParams.getOriginId).foreach(response.setOriginId)

      response
    }

  override def buildTargetTest(testParams: TestParams): CompletableFuture[TestResult] =
    handlerEvaluators() { (state, logger) =>
      testParams.setTargets(state.filterNonSynthetic(testParams.getTargets))
      val millBuildTargetIds = state.rootModules
        .map { case m: BspModuleApi => state.bspIdByModule(m) }
        .toSet

      val argsMap = parseTestClassArgs(testParams)

      val overallStatusCode = testParams.getTargets.asScala
        .filter(millBuildTargetIds.contains)
        .foldLeft(StatusCode.OK) { (accStatus, targetId) =>
          state.bspModulesById(targetId) match {
            case (testModule: TestModuleApi, ev) =>
              val testTask = testModule.testLocal(argsMap(targetId.getUri)*)
              val taskId = new TaskId(testTask.hashCode().toString)

              notifyTestStart(targetId, taskId)

              val testReporter = new BspTestReporter(client, targetId, taskId)
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

              notifyTestFinish(
                taskId,
                statusCode,
                testModule.bspBuildTarget.displayName,
                testReporter.getTestReport
              )

              combineStatusCodes(statusCode, accStatus)

            case _ => accStatus
          }
        }

      val testResult = new TestResult(overallStatusCode)
      Option(testParams.getOriginId).foreach(testResult.setOriginId)
      testResult
    }

  override def buildTargetCleanCache(cleanCacheParams: CleanCacheParams)
      : CompletableFuture[CleanCacheResult] =
    handlerEvaluators() { (state, logger) =>
      cleanCacheParams.setTargets(state.filterNonSynthetic(cleanCacheParams.getTargets))

      val results = cleanCacheParams.getTargets.asScala.map { targetId =>
        cleanSingleTarget(state, logger, targetId)
      }

      val allCleaned = results.forall(_.isRight)
      val message = results.map(_.merge).mkString

      new CleanCacheResult(allCleaned).tap(_.setMessage(message))
    }

  override def debugSessionStart(debugParams: DebugSessionParams)
      : CompletableFuture[DebugSessionAddress] =
    handlerEvaluators() { (state, _) =>
      debugParams.setTargets(state.filterNonSynthetic(debugParams.getTargets))
      throw new NotImplementedError("debugSessionStart endpoint is not implemented")
    }

  override def onRunReadStdin(params: ReadParams): Unit = {
    val logger = createLogger()
    logger.debug("onRunReadStdin is current unsupported")
  }

  // ==========================================================================
  // Endpoint Helpers
  // ==========================================================================

  /** Parses test class arguments from TestParams. */
  private def parseTestClassArgs(testParams: TestParams): Map[String, Seq[String]] =
    try {
      val scalaTestParams = testParams.getData.asInstanceOf[JsonObject]
      scalaTestParams.get("testClasses").getAsJsonArray.asScala.map { testItem =>
        val obj = testItem.getAsJsonObject
        val uri = obj.get("target").getAsJsonObject.get("uri").getAsString
        val classes = obj.get("classes").getAsJsonArray.asScala.map(_.getAsString).toSeq
        uri -> classes
      }.toMap
    } catch {
      case _: Exception =>
        testParams.getTargets.asScala.map(id => id.getUri -> Seq.empty[String]).toMap
    }

  /** Sends a task start notification for a test target */
  private def notifyTestStart(targetId: BuildTargetIdentifier, taskId: TaskId): Unit = {
    val params = new TaskStartParams(taskId)
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(s"Testing target: $targetId")
    params.setDataKind(TaskStartDataKind.TEST_TASK)
    params.setData(new TestTask(targetId))
    client.onBuildTaskStart(params)
  }

  /** Sends a task finish notification for a test target */
  private def notifyTestFinish(
      taskId: TaskId,
      statusCode: StatusCode,
      displayName: Option[String],
      testReport: TestReport
  ): Unit = {
    val params = new TaskFinishParams(taskId, statusCode)
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(s"Finished testing target${displayName.getOrElse("")}")
    params.setDataKind(TaskFinishDataKind.TEST_REPORT)
    params.setData(testReport)
    client.onBuildTaskFinish(params)
  }

  /** Cleans a single build target's cache. Returns Right(message) on success, Left(error) on failure. */
  private def cleanSingleTarget(
      state: BspEvaluators,
      logger: Logger,
      targetId: BuildTargetIdentifier
  ): Either[String, String] = {
    val (module, ev) = state.bspModulesById(targetId)
    val mainModule = ev.rootModule.asInstanceOf[mill.api.daemon.internal.MainModuleApi]
    val compileTaskName = (module.moduleSegments ++ Label("compile")).render
    logger.debug(s"about to clean: $compileTaskName")

    val cleanTask = mainModule.bspMainModule().bspClean(ev, Seq(compileTaskName)*)
    val cleanResult = evaluate(
      ev,
      s"Cleaning cache of ${module.bspDisplayName}",
      Seq(cleanTask),
      logger = logger,
      reporter = Utils.getBspLoggedReporterPool("", state.bspIdByModule, client)
    )

    if (cleanResult.transitiveFailingApi.nonEmpty) {
      val errorMsg = cleanResult.transitiveResultsApi(cleanTask) match {
        case ex: ExecResult.Exception => ex.toString()
        case ExecResult.Skipped => "Task was skipped"
        case ExecResult.Aborted => "Task was aborted"
        case _ => "could not retrieve the failure message"
      }
      Left(s" Target $compileTaskName could not be cleaned. See message from mill: \n$errorMsg")
    } else {
      val cleanedPaths = cleanResult.results.head.get.value.asInstanceOf[Seq[java.nio.file.Path]]
      while (cleanedPaths.exists(p => os.exists(os.Path(p)))) Thread.sleep(10)
      Right(s"${module.bspBuildTarget.displayName} cleaned \n")
    }
  }

}

object MillBspEndpoints {
  class SessionInfo(
      val clientType: ClientType,
      val clientWantsSemanticDb: Boolean,
      /** `true` when client and server support the `JvmCompileClasspathProvider` request. */
      val enableJvmCompileClasspathProvider: Boolean
  )
}
