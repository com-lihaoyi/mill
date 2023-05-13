package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildClient,
  BuildServer,
  BuildServerCapabilities,
  BuildTarget,
  BuildTargetCapabilities,
  BuildTargetIdentifier,
  CleanCacheParams,
  CleanCacheResult,
  CompileParams,
  CompileProvider,
  CompileResult,
  DebugProvider,
  DebugSessionAddress,
  DebugSessionParams,
  DependencyModule,
  DependencyModulesItem,
  DependencyModulesParams,
  DependencyModulesResult,
  DependencySourcesItem,
  DependencySourcesParams,
  DependencySourcesResult,
  InitializeBuildParams,
  InitializeBuildResult,
  InverseSourcesParams,
  InverseSourcesResult,
  OutputPathItem,
  OutputPathItemKind,
  OutputPathsItem,
  OutputPathsParams,
  OutputPathsResult,
  ResourcesItem,
  ResourcesParams,
  ResourcesResult,
  RunParams,
  RunProvider,
  RunResult,
  SourceItem,
  SourceItemKind,
  SourcesItem,
  SourcesParams,
  SourcesResult,
  StatusCode,
  TaskDataKind,
  TaskFinishParams,
  TaskId,
  TaskStartParams,
  TestParams,
  TestProvider,
  TestResult,
  TestTask,
  WorkspaceBuildTargetsResult
}
import ch.epfl.scala.bsp4j
import com.google.gson.JsonObject
import mill.T
import mill.api.{DummyTestReporter, PathRef, Result, Strict, internal}
import mill.define.Segment.Label
import mill.define.{Args, Discover, ExternalModule, Module, Segments, Task}
import mill.eval.Evaluator
import mill.main.{BspServerResult, MainModule}
import mill.scalalib.{GenIdeaImpl, JavaModule, SemanticDbJavaModule, TestModule}
import mill.scalalib.bsp.{BspModule, JvmBuildTarget, ScalaBuildTarget}
import mill.runner.MillBuildRootModule
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import Utils.sanitizeUri
@internal
class MillBuildServer(
    initialEvaluator: Option[Evaluator],
    bspVersion: String,
    serverVersion: String,
    serverName: String,
    logStream: PrintStream,
    canReload: Boolean
) extends ExternalModule
    with BuildServer {

  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]

  private[worker] var cancellator: Boolean => Unit = shutdownBefore => ()
  private[worker] var onSessionEnd: Option[BspServerResult => Unit] = None
  protected var client: BuildClient = _
  private var initialized = false
  private var clientInitialized = false
  private var shutdownRequested = false
  protected var clientWantsSemanticDb = false
  protected var clientIsIntelliJ = false

  private[this] var statePromise: Promise[State] = Promise[State]()
  var evaluatorOpt: Option[Evaluator] = None
  def evaluator = evaluatorOpt.get
  updateEvaluator(initialEvaluator)
  def updateEvaluator(evaluator: Option[Evaluator]): Unit = {
    debug(s"Updating Evaluator: $evaluator")
    if (statePromise.isCompleted) {
      // replace the promise
      statePromise = Promise[State]()
    }
    evaluatorOpt = evaluatorOpt
    evaluator.foreach(e => statePromise.success(new State(e.rootModule.millSourcePath, e.baseLogger, debug)))
  }

  def debug(msg: String) = logStream.println(msg)


  override def onConnectWithClient(buildClient: BuildClient): Unit = client = buildClient

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    completableNoState(s"buildInitialize ${request}", checkInitialized = false) {

      // TODO: scan BspModules and infer their capabilities

      val clientCaps = request.getCapabilities().getLanguageIds().asScala

//      val compileLangs = moduleBspInfo.filter(_.canCompile).flatMap(_.languageIds).distinct.filter(
//        clientCaps.contains
//      )
//      val runLangs =
//        moduleBspInfo.filter(_.canRun).flatMap(
//          _.languageIds
//        ).distinct // .filter(clientCaps.contains)
//      val testLangs =
//        moduleBspInfo.filter(_.canTest).flatMap(
//          _.languageIds
//        ).distinct //.filter(clientCaps.contains)
//      val debugLangs =
//        moduleBspInfo.filter(_.canDebug).flatMap(
//          _.languageIds
//        ).distinct //.filter(clientCaps.contains)

      val supportedLangs = Seq("java", "scala").asJava
      val capabilities = new BuildServerCapabilities

      capabilities.setBuildTargetChangedProvider(false)
      capabilities.setCanReload(canReload)
      capabilities.setCompileProvider(new CompileProvider(supportedLangs))
      capabilities.setDebugProvider(new DebugProvider(Seq().asJava))
      capabilities.setDependencyModulesProvider(true)
      capabilities.setDependencySourcesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setJvmRunEnvironmentProvider(true)
      capabilities.setJvmTestEnvironmentProvider(true)
      capabilities.setOutputPathsProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setRunProvider(new RunProvider(supportedLangs))
      capabilities.setTestProvider(new TestProvider(supportedLangs))

      // IJ is currently not able to handle files as source paths, only dirs
      // TODO: Rumor has it, that newer version may handle it, so we need to better detect that
      clientIsIntelliJ = request.getDisplayName == "IntelliJ-BSP"

      def readVersion(json: JsonObject, name: String): Option[String] =
        if (json.has(name)) {
          val rawValue = json.get(name)
          if (rawValue.isJsonPrimitive) {
            val version = Try(rawValue.getAsJsonPrimitive.getAsString).toOption.filter(_.nonEmpty)
            debug(s"Got json value for ${name}=${version}")
            version
          } else None
        } else None

      request.getData match {
        case d: JsonObject =>
          debug(s"extra data: ${d} of type ${d.getClass}")
          readVersion(d, "semanticdbVersion").foreach { version =>
            debug(
              s"Got client semanticdbVersion: ${version}. Enabling SemanticDB support."
            )
            clientWantsSemanticDb = true
            SemanticDbJavaModule.contextSemanticDbVersion.set(Option(version))
          }
          readVersion(d, "javaSemanticdbVersion").foreach { version =>
            SemanticDbJavaModule.contextJavaSemanticDbVersion.set(Option(version))
          }
        case _ => // no op
      }

      initialized = true
      new InitializeBuildResult(serverName, serverVersion, bspVersion, capabilities)
    }

  override def onBuildInitialized(): Unit = {
    clientInitialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    debug(s"Entered buildShutdown")
    shutdownRequested = true
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        debug("Shutdown build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModule.resetContext()
    CompletableFuture.completedFuture(null.asInstanceOf[Object])
  }
  override def onBuildExit(): Unit = {
    debug("Entered onBuildExit")
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        debug("Exiting build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModule.resetContext()
    cancellator(shutdownRequested)
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completableTasks(
      "workspaceBuildTargets",
      targetIds = _.bspModulesById.keySet.toSeq,
      agg = (items: Seq[BuildTarget]) => new WorkspaceBuildTargetsResult(items.asJava),
      tasks = {case m: JavaModule  => T.task{m.bspBuildTargetData()}}
    ) { (state, id, m, bspBuildTargetData) =>
        val s = m.bspBuildTarget
        val deps = m match {
          case jm: JavaModule =>
            jm.recursiveModuleDeps.collect { case bm: BspModule => state.bspIdByModule(bm)}
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
            Some((dataKind, target))

          case Some((dataKind, d: JvmBuildTarget)) =>
            val target = new bsp4j.JvmBuildTarget(
              d.javaHome.map(_.uri).getOrElse(null),
              d.javaVersion.getOrElse(null)
            )
            Some((dataKind, target))

          case Some((dataKind, d)) => None // unsupported data kind
          case None => None
        }

        val buildTarget = new BuildTarget(
          id,
          s.tags.asJava,
          s.languageIds.asJava,
          deps.asJava,
          new BuildTargetCapabilities(s.canCompile, s.canTest, s.canRun, s.canDebug)
        )

        s.displayName.foreach(buildTarget.setDisplayName)
        s.baseDirectory.foreach(p => buildTarget.setBaseDirectory(sanitizeUri(p)))

        for((dataKind, data) <- data) {
          buildTarget.setDataKind(dataKind)
          buildTarget.setData(data)
        }

        buildTarget


    }

  override def workspaceReload(): CompletableFuture[Object] =
    completableNoState("workspaceReload", false) {
      // Instead stop and restart the command
      // BSP.install(evaluator)
      onSessionEnd match {
        case None => "unsupportedWorkspaceReload".asInstanceOf[Object]
        case Some(onEnd) =>
          debug("Reloading workspace...")
          onEnd(BspServerResult.ReloadWorkspace).asInstanceOf[Object]
      }
    }

  override def buildTargetSources(sourcesParams: SourcesParams)
      : CompletableFuture[SourcesResult] = {

    def sourceItem(source: os.Path, generated: Boolean) = {
      new SourceItem(
        sanitizeUri(source),
        if (source.toIO.isFile) SourceItemKind.FILE else SourceItemKind.DIRECTORY,
        generated
      )
    }

    completableTasks(
      hint = s"buildTargetSources ${sourcesParams}",
      targetIds = _ => sourcesParams.getTargets.asScala.toSeq,
      agg = (items: Seq[SourcesItem]) => new SourcesResult(items.asJava),
      tasks = {
        case module: MillBuildRootModule =>
          T.task {
            module.scriptSources().map(p => sourceItem(p.path, false)) ++
              module.sources().map(p => sourceItem(p.path, false)) ++
              module.generatedSources().map(p => sourceItem(p.path, true))
          }
        case module: JavaModule =>
          T.task{
            module.sources().map(p => sourceItem(p.path, false)) ++
            module.generatedSources().map(p => sourceItem(p.path, true))
          }
      }
    ) {
      case (state, id, module, items) => new SourcesItem(id, items.asJava)
    }

  }

  override def buildTargetInverseSources(p: InverseSourcesParams)
      : CompletableFuture[InverseSourcesResult] = {
    completable(s"buildtargetInverseSources ${p}") { state =>
      val tasks = state.bspModulesById.iterator.collect {
        case (id, m: JavaModule) =>
          T.task {
            val src = m.allSourceFiles()
            val found = src.map(sanitizeUri).contains(
              p.getTextDocument.getUri
            )
            if (found) Seq((id)) else Seq()
          }
      }.toSeq

      val ids = evaluator.evalOrThrow()(tasks).flatten
      new InverseSourcesResult(ids.asJava)
    }
  }

  /**
   * External dependencies (sources or source jars).
   */
  override def buildTargetDependencySources(p: DependencySourcesParams)
      : CompletableFuture[DependencySourcesResult] =
    completableTasks(
      hint = s"buildTargetDependencySources ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      agg = (items: Seq[DependencySourcesItem]) => new DependencySourcesResult(items.asJava),
      tasks = {case m: JavaModule =>
        T.task {
          (m.resolveDeps(
            T.task(m.transitiveCompileIvyDeps() ++ m.transitiveIvyDeps()),
            sources = true
          )(),
            m.unmanagedClasspath()
          )
        }
      }
    ) {
      case (state, id, m: JavaModule, (resolveDepsSources, unmanagedClasspath)) =>
        val buildSources =
          if (!m.isInstanceOf[MillBuildRootModule]) Nil
          else mill.scalalib.Lib
            .resolveMillBuildDeps(Nil, None, useSources = true)
            .map(sanitizeUri(_))

        val cp = (resolveDepsSources ++ unmanagedClasspath).map(sanitizeUri).toSeq ++ buildSources
        new DependencySourcesItem(id, cp.asJava)
    }


  /**
   * External dependencies per module (e.g. ivy deps)
   */
  override def buildTargetDependencyModules(params: DependencyModulesParams)
      : CompletableFuture[DependencyModulesResult] =
    completableTasks(
      hint = "buildTargetDependencyModules",
      targetIds = _ =>params.getTargets.asScala.toSeq,
      agg = (items: Seq[DependencyModulesItem]) => new DependencyModulesResult(items.asJava),
      tasks = {case m: JavaModule =>
        T.task {(m.transitiveCompileIvyDeps(), m.transitiveIvyDeps(), m.unmanagedClasspath())}
      }
    ) {
      case (state, id, m: JavaModule, (transitiveCompileIvyDeps, transitiveIvyDeps, unmanagedClasspath)) =>
        val ivy = transitiveCompileIvyDeps ++ transitiveIvyDeps
        val deps = ivy.map { dep =>
          new DependencyModule(dep.dep.module.repr, dep.dep.version)
        }
        val unmanged = unmanagedClasspath.map { dep =>
          new DependencyModule(s"unmanaged-${dep.path.last}", "")
        }
        new DependencyModulesItem(id, (deps ++ unmanged).iterator.toSeq.asJava)
    }

  override def buildTargetResources(p: ResourcesParams): CompletableFuture[ResourcesResult] =
    completableTasks(
      s"buildTargetResources ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      agg = (items: Seq[ResourcesItem]) => new ResourcesResult(items.asJava),
      tasks = {
        case m: JavaModule => T.task{ m.resources() }
        case _ => T.task{ Nil }
      }
    ) {
      case (state, id, m, resources) =>
        val resourcesUrls = resources.map(_.path).filter(os.exists).map(sanitizeUri)
        new ResourcesItem(id, resourcesUrls.asJava)

    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] =
    completable(s"buildTargetCompile ${p}") { state =>

      val params = TaskParameters.fromCompileParams(p)
      val taskId = params.hashCode()
      val compileTasks = params.getTargets.distinct.map(state.bspModulesById).map {
        case m: SemanticDbJavaModule if clientWantsSemanticDb => m.compiledClassesAndSemanticDbFiles
        case m: JavaModule => m.compile
        case m => T.task {
            Result.Failure(
              s"Don't know how to compile non-Java target ${m.bspBuildTarget.displayName}"
            )
          }
      }

      val result = evaluator.evaluate(
        compileTasks,
        Utils.getBspLoggedReporterPool(p.getOriginId, state.bspIdByModule, client),
        DummyTestReporter,
        new MillBspLogger(client, taskId, evaluator.baseLogger)
      )
      val compileResult = new CompileResult(Utils.getStatusCode(result))
      compileResult.setOriginId(p.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetOutputPaths(params: OutputPathsParams)
      : CompletableFuture[OutputPathsResult] =
    completable(s"buildTargetOutputPaths ${params}") { state =>
      val outItems = new OutputPathItem(
        // Spec says, a directory must end with a forward slash
        sanitizeUri(evaluator.outPath) + "/",
        OutputPathItemKind.DIRECTORY
      )

      val extItems = new OutputPathItem(
        // Spec says, a directory must end with a forward slash
        sanitizeUri(evaluator.externalOutPath) + "/",
        OutputPathItemKind.DIRECTORY
      )

      val items = for {
        target <- params.getTargets.asScala
        module <- state.bspModulesById.get(target)
      } yield {
        val items =
          if (module.millOuterCtx.external) List(extItems)
          else List(outItems)
        new OutputPathsItem(target, items.asJava)
      }

      new OutputPathsResult(items.asJava)
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    completable(s"buildTargetRun ${runParams}") { state =>
      val params = TaskParameters.fromRunParams(runParams)
      val module = params.getTargets.map(state.bspModulesById).collectFirst {
        case m: JavaModule => m
      }.get
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(T.task(Args(args)))
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        Utils.getBspLoggedReporterPool(runParams.getOriginId, state.bspIdByModule, client),
        logger = new MillBspLogger(client, runTask.hashCode(), evaluator.baseLogger)
      )
      val response = runResult.results(runTask) match {
        case _: Result.Success[Any] => new RunResult(StatusCode.OK)
        case _ => new RunResult(StatusCode.ERROR)
      }
      params.getOriginId match {
        case Some(id) => response.setOriginId(id)
        case None =>
      }

      response
    }

  override def buildTargetTest(testParams: TestParams): CompletableFuture[TestResult] =
    completable(s"buildTargetTest ${testParams}") { state =>

      val modules = state.bspModulesById.values.toSeq.collect { case m: JavaModule => m }
      val millBuildTargetIds = state.rootModules.map { case m: BspModule => state.bspIdByModule(m) }.toSet

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
            case testModule: TestModule =>
              val testTask = testModule.testLocal(argsMap(targetId.getUri): _*)

              // notifying the client that the testing of this build target started
              val taskStartParams = new TaskStartParams(new TaskId(testTask.hashCode().toString))
              taskStartParams.setEventTime(System.currentTimeMillis())
              taskStartParams.setMessage("Testing target: " + targetId)
              taskStartParams.setDataKind(TaskDataKind.TEST_TASK)
              taskStartParams.setData(new TestTask(targetId))
              client.onBuildTaskStart(taskStartParams)

              val testReporter =
                new BspTestReporter(
                  client,
                  targetId,
                  new TaskId(testTask.hashCode().toString),
                  Seq.empty[String]
                )

              val results = evaluator.evaluate(
                Strict.Agg(testTask),
                Utils.getBspLoggedReporterPool(
                  testParams.getOriginId,
                  state.bspIdByModule,
                  client
                ),
                testReporter,
                new MillBspLogger(client, testTask.hashCode, evaluator.baseLogger)
              )
              val statusCode = Utils.getStatusCode(results)

              // Notifying the client that the testing of this build target ended
              val taskFinishParams =
                new TaskFinishParams(new TaskId(testTask.hashCode().toString), statusCode)
              taskFinishParams.setEventTime(System.currentTimeMillis())
              taskFinishParams.setMessage(
                s"Finished testing target${testModule.bspBuildTarget.displayName}"
              )
              taskFinishParams.setDataKind(TaskDataKind.TEST_REPORT)
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
    completable(s"buildTargetCleanCache ${cleanCacheParams}") { state =>

      val targetIds = state.rootModules.map { case b: BspModule => state.bspIdByModule(b) }
      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.filter(targetIds.contains).foldLeft((
          "",
          true
        )) {
          case ((msg, cleaned), targetId) =>
            val module = state.bspModulesById(targetId)
            val mainModule = new MainModule {
              override implicit def millDiscover: Discover[_] = Discover[this.type]
            }
            val compileTargetName = (module.millModuleSegments ++ Label("compile")).render
            debug(s"about to clean: ${compileTargetName}")
            val cleanTask = mainModule.clean(evaluator, Seq(compileTargetName): _*)
            val cleanResult = evaluator.evaluate(
              Strict.Agg(cleanTask),
              logger = new MillBspLogger(client, cleanTask.hashCode, evaluator.baseLogger)
            )
            if (cleanResult.failing.keyCount > 0) (
              msg + s" Target ${compileTargetName} could not be cleaned. See message from mill: \n" +
                (cleanResult.results(cleanTask) match {
                  case fail: Result.Failure[Any] => fail.msg + "\n"
                  case _ => "could not retrieve message"
                }),
              false
            )
            else {
              val outPaths = evaluator.pathsResolver.resolveDest(
                module.millModuleSegments ++ Label("compile")
              )
              val outPathSeq = Seq(outPaths.dest, outPaths.meta, outPaths.log)

              while (outPathSeq.exists(os.exists(_))) Thread.sleep(10)

              (msg + s"${module.bspBuildTarget.displayName} cleaned \n", cleaned)
            }
        }

      new CleanCacheResult(msg, cleaned)
    }

  override def debugSessionStart(debugParams: DebugSessionParams)
      : CompletableFuture[DebugSessionAddress] =
    completable(s"debugSessionStart ${debugParams}") { state =>
      throw new NotImplementedError("debugSessionStart endpoint is not implemented")
    }

  def completableTasks[T, V, W: ClassTag](
      hint: String,
      targetIds: State => Seq[BuildTargetIdentifier],
      agg: Seq[T] => V,
      tasks: BspModule => Task[W]
  )(f: (State, BuildTargetIdentifier, BspModule, W) => T): CompletableFuture[V] =
    completable(hint) { state: State =>
      val ids = targetIds(state)
      val tasksSeq = ids
        .map(state.bspModulesById)
        .map(m => tasks(m))

      val evaluated = evaluator.evalOrThrow()(tasksSeq)
      val res = evaluated.zip(ids).map{case (v, i) => f(state, i, state.bspModulesById(i), v)}
      agg(res)
    }

  /**
   * Given a function that take input of type T and return output of type V,
   * apply the function on the given inputs and return a completable future of
   * the result. If the execution of the function raises an Exception, complete
   * the future exceptionally. Also complete exceptionally if the server was not
   * yet initialized.
   */
  protected def completable[V](
      hint: String,
      checkInitialized: Boolean = true
  )(f: State => V): CompletableFuture[V] = {
    debug(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      debug(s"${prefix} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()

    if (checkInitialized && !initialized) {
      future.completeExceptionally(
        new Exception(
          s"Can not respond to ${prefix} request before receiving the `initialize` request."
        )
      )
    } else {
      statePromise.future.onComplete {
        case Success(state) =>
          try {
            val v = f(state)
            took
            debug(s"${prefix} result: ${v}")
            future.complete(v)
          } catch {
            case e: Exception =>
              took
              logStream.println(s"${prefix} caught exception: ${e}")
              e.printStackTrace(logStream)
              future.completeExceptionally(e)
          }
        case Failure(exception) =>
          future.completeExceptionally(exception)
      }(scala.concurrent.ExecutionContext.global)

    }

    future
  }

  protected def completableNoState[V](
      hint: String,
      checkInitialized: Boolean = true
  )(f: => V): CompletableFuture[V] = {
    debug(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      debug(s"${prefix} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()

    if (checkInitialized && !initialized) {
      future.completeExceptionally(
        new Exception(
          s"Can not respond to ${prefix} request before receiving the `initialize` request."
        )
      )
    } else {
      try {
        val v = f
        took
        debug(s"${prefix} result: ${v}")
        future.complete(v)
      } catch {
        case e: Exception =>
          took
          logStream.println(s"${prefix} caught exception: ${e}")
          e.printStackTrace(logStream)
          future.completeExceptionally(e)
      }
    }

    future
  }
}
