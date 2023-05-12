package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildClient, BuildServer, BuildServerCapabilities, BuildTarget, BuildTargetCapabilities, BuildTargetIdentifier, CleanCacheParams, CleanCacheResult, CompileParams, CompileProvider, CompileResult, DebugProvider, DebugSessionAddress, DebugSessionParams, DependencyModule, DependencyModulesItem, DependencyModulesParams, DependencyModulesResult, DependencySourcesItem, DependencySourcesParams, DependencySourcesResult, InitializeBuildParams, InitializeBuildResult, InverseSourcesParams, InverseSourcesResult, OutputPathItem, OutputPathItemKind, OutputPathsItem, OutputPathsParams, OutputPathsResult, ResourcesItem, ResourcesParams, ResourcesResult, RunParams, RunProvider, RunResult, SourceItem, SourceItemKind, SourcesItem, SourcesParams, SourcesResult, StatusCode, TaskDataKind, TaskFinishParams, TaskId, TaskStartParams, TestParams, TestProvider, TestResult, TestTask, WorkspaceBuildTargetsResult}
import ch.epfl.scala.bsp4j
import com.google.gson.JsonObject
import mill.T
import mill.api.{DummyTestReporter, PathRef, Result, Strict, internal}
import mill.define.Segment.Label
import mill.define.{Args, Discover, ExternalModule, Module, Segments, Task}
import mill.eval.Evaluator
import mill.main.{BspServerResult, MainModule}
import mill.scalalib.{JavaModule, SemanticDbJavaModule, TestModule}
import mill.scalalib.bsp.{BspModule, JvmBuildTarget, ScalaBuildTarget}
import mill.scalalib.internal.ModuleUtils
import mill.runner.{MillBuildBootstrap, MillBuildRootModule}
import os.{Path, root}

import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Failure, Success, Try}

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

  class State(val evaluator: Evaluator) {
    private[this] object internal {
      def init(): Unit = synchronized {
        idToModule match {
          case None =>

            val modules: Seq[(Module, Seq[Module])] = rootModules
              .map(rootModule => (rootModule, ModuleUtils.transitiveModules(rootModule)))

            val map = modules
              .flatMap { case (rootModule, otherModules) =>
                (Seq(rootModule) ++ otherModules).collect {
                  case m: BspModule =>
                    val uri = sanitizeUri(
                      rootModule.millSourcePath / m.millModuleSegments.parts
                    )

                    val id = new BuildTargetIdentifier(uri)

                    (id, m)
                }
              }
              .toMap
            idToModule = Some(map)
            modulesToId = Some(map.map(_.swap).toMap)
            log.debug(s"BspModules: ${map}")
          case _ => // already init
        }
      }
      private[MillBuildServer] var idToModule: Option[Map[BuildTargetIdentifier, BspModule]] = None
      private[MillBuildServer] var modulesToId: Option[Map[BspModule, BuildTargetIdentifier]] = None
    }

    lazy val rootModules: Seq[mill.main.RootModule] = {
      val evaluated = new mill.runner.MillBuildBootstrap(
        projectRoot = evaluator.rootModule.millSourcePath,
        home = os.home,
        keepGoing = false,
        imports = Nil,
        env = Map.empty,
        threadCount = None,
        targetsAndParams = Seq("resolve", "_"),
        prevRunnerState = mill.runner.RunnerState.empty,
        logger = evaluator.baseLogger
      ).evaluate()

      val rootModules0 = evaluated.result.frames
        .flatMap(_.classLoaderOpt)
        .zipWithIndex
        .map{case (c, i) =>
          MillBuildBootstrap
            .getRootModule(c, i, evaluator.rootModule.millSourcePath)
              .fold(sys.error(_), identity(_))
        }

      val bootstrapModule = evaluated.result.bootstrapModuleOpt.map(m =>
        MillBuildBootstrap
          .getChildRootModule(m, evaluated.result.frames.length, evaluator.rootModule.millSourcePath)
          .fold(sys.error(_), identity(_))
      )

      rootModules0 ++ bootstrapModule
    }
    def bspModulesById: Map[BuildTargetIdentifier, BspModule] = {
      internal.init()
      internal.idToModule.get
    }
    def bspIdByModule: Map[BspModule, BuildTargetIdentifier] = {
      internal.init()
      internal.modulesToId.get
    }
  }

  private[this] var statePromise: Promise[State] = Promise[State]()
  initialEvaluator.foreach(e => statePromise.success(new State(e)))

  def updateEvaluator(evaluator: Option[Evaluator]): Unit = {
    log.debug(s"Updating Evaluator: ${evaluator}")
    if (statePromise.isCompleted) {
      // replace the promise
      statePromise = Promise[State]()
    }
    evaluator.foreach(e => statePromise.success(new State(e)))
  }

  object log {
    def debug(msg: String) = logStream.println(msg)
  }

  object sanitizeUri {
    def apply(uri: String): String =
      if (uri.endsWith("/")) apply(uri.substring(0, uri.length - 1)) else uri
    def apply(uri: os.Path): String = apply(uri.toNIO.toUri.toString)
    def apply(uri: PathRef): String = apply(uri.path)
  }

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
            log.debug(s"Got json value for ${name}=${version}")
            version
          } else None
        } else None

      request.getData match {
        case d: JsonObject =>
          log.debug(s"extra data: ${d} of type ${d.getClass}")
          readVersion(d, "semanticdbVersion").foreach { version =>
            log.debug(
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
    log.debug(s"Entered buildShutdown")
    shutdownRequested = true
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        log.debug("Shutdown build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModule.resetContext()
    CompletableFuture.completedFuture(null.asInstanceOf[Object])
  }
  override def onBuildExit(): Unit = {
    log.debug("Entered onBuildExit")
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        log.debug("Exiting build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModule.resetContext()
    cancellator(shutdownRequested)
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completable("workspaceBuildTargets") { state: State =>
      import state._
      targetTasks(
        state,
        targetIds = state.bspModulesById.keySet.toSeq,
        agg = (items: Seq[BuildTarget]) => new WorkspaceBuildTargetsResult(items.asJava)
      ) {
        case (id, m) =>
          T.task {
            val s = m.bspBuildTarget
            val deps = m match {
              case jm: JavaModule =>
                jm.recursiveModuleDeps.collect {
                  case bm: BspModule => bspIdByModule(bm)
                }
              case _ => Seq()
            }
            val data = m.bspBuildTargetData() match {
              case Some((dataKind, d: ScalaBuildTarget)) =>
                Some((
                  dataKind,
                  new bsp4j.ScalaBuildTarget(
                    d.scalaOrganization,
                    d.scalaVersion,
                    d.scalaBinaryVersion,
                    bsp4j.ScalaPlatform.forValue(d.platform.number),
                    d.jars.asJava
                  )
                ))
              case Some((dataKind, d: JvmBuildTarget)) =>
                Some((
                  dataKind,
                  new bsp4j.JvmBuildTarget(
                    d.javaHome.map(_.uri).getOrElse(null),
                    d.javaVersion.getOrElse(null)
                  )
                ))
              case Some((dataKind, d)) =>
                // unsupported data kind
                None
              case None => None
            }

            new BuildTarget(
              id,
              s.tags.asJava,
              s.languageIds.asJava,
              deps.asJava,
              new BuildTargetCapabilities(s.canCompile, s.canTest, s.canRun, s.canDebug)
            ).tap { t =>
              s.displayName.foreach(t.setDisplayName)
              s.baseDirectory.foreach(p => t.setBaseDirectory(sanitizeUri(p)))
              data.foreach { d =>
                t.setDataKind(d._1)
                t.setData(d._2)
              }
            }
          }
      }
    }

  override def workspaceReload(): CompletableFuture[Object] =
    completableNoState("workspaceReload", false) {
      // Instead stop and restart the command
      // BSP.install(evaluator)
      onSessionEnd match {
        case None => "unsupportedWorkspaceReload".asInstanceOf[Object]
        case Some(onEnd) =>
          log.debug("Reloading workspace...")
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

    completable(hint = s"buildTargetSources ${sourcesParams}") { state: State =>
      import state._
      targetTasks(
        state,
        targetIds = sourcesParams.getTargets.asScala.toSeq,
        agg = (items: Seq[SourcesItem]) => new SourcesResult(items.asJava)
      ) {
        case (id, module: MillBuildRootModule) if clientIsIntelliJ =>
          T.task {
            val sources = new SourcesItem(
              id,
              module.dummySources().map(p => sourceItem(p.path, true)).asJava
            )
            sources.setRoots(Seq(sanitizeUri(evaluator.rootModule.millSourcePath)).asJava)
            sources
          }
        case (id, module: MillBuildRootModule) =>
          T.task {
            val items =
              module.scriptSources().map(p => sourceItem(p.path, false)) ++
              module.sources().map(p => sourceItem(p.path, false)) ++
              module.generatedSources().map(p => sourceItem(p.path, true))
            new SourcesItem(id, items.asJava)
          }
        case (id, module: JavaModule) =>
          T.task {
            val items = module.sources().map(p => sourceItem(p.path, false)) ++
              module.generatedSources().map(p => sourceItem(p.path, true))
            new SourcesItem(id, items.asJava)
          }
      }
    }
  }

  override def buildTargetInverseSources(p: InverseSourcesParams)
      : CompletableFuture[InverseSourcesResult] = {
    completable(s"buildtargetInverseSources ${p}") { state =>
      import state._

      val tasks = bspModulesById.iterator.collect {
        case (id, m: JavaModule) =>
          T.task {
            val src = m.allSourceFiles()
            val found = src.map(sanitizeUri.apply).contains(
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
    completable(hint = s"buildTargetDependencySources ${p}") { state: State =>
      targetTasks(
        state,
        targetIds = p.getTargets.asScala.toSeq,
        agg = (items: Seq[DependencySourcesItem]) => new DependencySourcesResult(items.asJava)
      ) {
        case (id, m: JavaModule) =>
          T.task {
            val sources = m.resolveDeps(
              T.task(m.transitiveCompileIvyDeps() ++ m.transitiveIvyDeps()),
              sources = true
            )()
            val unmanaged = m.unmanagedClasspath()
            val cp = (sources ++ unmanaged).map(sanitizeUri.apply).iterator.toSeq
            new DependencySourcesItem(id, cp.asJava)
          }
      }
    }

  /**
   * External dependencies per module (e.g. ivy deps)
   */
  override def buildTargetDependencyModules(params: DependencyModulesParams)
      : CompletableFuture[DependencyModulesResult] =
    completableTasks(
      hint = "buildTargetDependencyModules",
      targetIds = params.getTargets.asScala.toSeq,
      agg = (items: Seq[DependencyModulesItem]) => new DependencyModulesResult(items.asJava)
    ) {
      case (id, m: JavaModule) =>
        T.task {
          val ivy = m.transitiveCompileIvyDeps() ++ m.transitiveIvyDeps()
          val deps = ivy.map { dep =>
            new DependencyModule(dep.dep.module.repr, dep.dep.version)
          }
          val unmanged = m.unmanagedClasspath().map { dep =>
            new DependencyModule(s"unmanaged-${dep.path.last}", "")
          }
          new DependencyModulesItem(id, (deps ++ unmanged).iterator.toSeq.asJava)
        }
    }

  override def buildTargetResources(p: ResourcesParams): CompletableFuture[ResourcesResult] =
    completableTasks(
      s"buildTargetResources ${p}",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ResourcesItem]) => new ResourcesResult(items.asJava)
    ) {
      case (id, m: JavaModule) => T.task {
          val resources = m.resources().map(_.path).filter(os.exists).map(sanitizeUri.apply)
          new ResourcesItem(id, resources.asJava)
        }
      case (id, _) => T.task {
          // no java module, no resources
          new ResourcesItem(id, Seq.empty[String].asJava)
        }
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] =
    completable(s"buildTargetCompile ${p}") { state =>
      import state._

      val params = TaskParameters.fromCompileParams(p)
      val taskId = params.hashCode()
      val compileTasks = params.getTargets.distinct.map(bspModulesById).map {
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
        Utils.getBspLoggedReporterPool(p.getOriginId, bspIdByModule, client),
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
      import state._

      val outItems = new OutputPathItem(
        // Spec says, a directory must end with a forward slash
        sanitizeUri.apply(evaluator.outPath) + "/",
        OutputPathItemKind.DIRECTORY
      )

      val extItems = new OutputPathItem(
        // Spec says, a directory must end with a forward slash
        sanitizeUri.apply(evaluator.externalOutPath) + "/",
        OutputPathItemKind.DIRECTORY
      )

      val items = for {
        target <- params.getTargets.asScala
        module <- bspModulesById.get(target)
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
      import state._

      val params = TaskParameters.fromRunParams(runParams)
      val module = params.getTargets.map(bspModulesById).collectFirst {
        case m: JavaModule => m
      }.get
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(T.task(Args(args)))
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        Utils.getBspLoggedReporterPool(runParams.getOriginId, bspIdByModule, client),
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
      import state._

      val modules = bspModulesById.values.toSeq.collect { case m: JavaModule => m }
      val millBuildTargetIds = rootModules.map{case m: BspModule => bspIdByModule(m)}.toSet

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
          bspModulesById(targetId) match {
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
                Utils.getBspLoggedReporterPool(testParams.getOriginId, bspIdByModule, client),
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
      import state._

      val targetIds = rootModules.map{case b: BspModule => bspIdByModule(b)}
      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.filter(targetIds.contains).foldLeft((
          "",
          true
        )) {
          case ((msg, cleaned), targetId) =>
            val module = bspModulesById(targetId)
            val mainModule = new MainModule {
              override implicit def millDiscover: Discover[_] = Discover[this.type]
            }
            val compileTargetName = (module.millModuleSegments ++ Label("compile")).render
            log.debug(s"about to clean: ${compileTargetName}")
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

  def completableTasks[T: ClassTag, V](
      hint: String,
      targetIds: Seq[BuildTargetIdentifier],
      agg: Seq[T] => V
  )(f: (BuildTargetIdentifier, BspModule) => Task[T]): CompletableFuture[V] =
    completable(hint) { state: State =>
      targetTasks(state, targetIds, agg)(f)
    }

  def targetTasks[T: ClassTag, V](
      state: State,
      targetIds: Seq[BuildTargetIdentifier],
      agg: Seq[T] => V
  )(f: (BuildTargetIdentifier, BspModule) => Task[T]): V = {
    import state._
    val tasks: Seq[Task[T]] = targetIds.distinct.map(id => f(id, bspModulesById(id)))
    val res = evaluator.evalOrThrow()(tasks)
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
    log.debug(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      log.debug(s"${prefix} took ${System.currentTimeMillis() - start} msec")

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
            log.debug(s"${prefix} result: ${v}")
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
    log.debug(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      log.debug(s"${prefix} took ${System.currentTimeMillis() - start} msec")

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
        log.debug(s"${prefix} result: ${v}")
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
