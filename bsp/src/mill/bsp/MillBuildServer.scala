package mill.bsp

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
import com.google.gson.JsonObject

import java.util.concurrent.CompletableFuture
import mill._
import mill.api.{DummyTestReporter, Result, Strict}
import mill.define.Segment.Label
import mill.define.{BaseModule, Discover, ExternalModule, Segments, Task}
import mill.eval.Evaluator
import mill.scalalib.internal.ModuleUtils
import mill.main.{BspServerResult, EvaluatorScopt, MainModule}
import mill.scalalib.{JavaModule, SemanticDbJavaModule, TestModule}
import mill.scalalib.bsp.{BspModule, MillBuildTarget}
import os.Path

import java.io.PrintStream
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import scala.util.chaining.scalaUtilChainingOps

class MillBuildServer(
    initialEvaluator: Option[Evaluator],
    bspVersion: String,
    serverVersion: String,
    serverName: String,
    logStream: PrintStream,
    canReload: Boolean
) extends ExternalModule
    with BuildServer {

  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]
  var cancellator: Boolean => Unit = shutdownBefore => ()
  var onSessionEnd: Option[BspServerResult => Unit] = None
  var client: BuildClient = _
  var initialized = false
  var clientInitialized = false
  var shutdownRequested = false
  var clientWantsSemanticDb = false
  var clientIsIntelliJ = false

  class State(val evaluator: Evaluator) {
    private[this] object internal {
      def init(): Unit = synchronized {
        idToModule match {
          case None =>
            val modules: Seq[Module] =
              ModuleUtils.transitiveModules(evaluator.rootModule) ++ Seq(`mill-build`)
            val map = modules.collect {
              case m: MillBuildTarget =>
                val uri = sanitizeUri(m.millSourcePath) +
                  m.bspBuildTarget.displayName.map(n => s"?id=${n}").getOrElse("")
                val id = new BuildTargetIdentifier(uri)
                log.debug(s"mill-build segments: ${`mill-build`.millModuleSegments.render}")
                (id, m)
              case m: BspModule =>
                val uri = sanitizeUri(`mill-build`.millSourcePath / m.millModuleSegments.parts) +
                  m.bspBuildTarget.displayName.map(n => s"?id=${n}").getOrElse("")
                val id = new BuildTargetIdentifier(uri)
                (id, m)
            }.toMap
            idToModule = Some(map)
            modulesToId = Some(map.map(_.swap).toMap)
            log.debug(s"BspModules: ${map}")
          case _ => // already init
        }
      }
      private[MillBuildServer] var idToModule: Option[Map[BuildTargetIdentifier, BspModule]] =
        None
      private[MillBuildServer] var modulesToId: Option[Map[BspModule, BuildTargetIdentifier]] = None

    }

    lazy val `mill-build`: MillBuildTarget = {
      object `mill-build` extends MillBuildTarget {
        override protected def rootModule: BaseModule = evaluator.rootModule
      }
      `mill-build`
    }

    def bspModulesById: Map[BuildTargetIdentifier, BspModule] = {
      internal.init()
      internal.idToModule.get
    }
    def bspIdByModule: Map[BspModule, BuildTargetIdentifier] = {
      internal.init()
      internal.modulesToId.get
    }

    /** Convert to BSP API. */
    implicit class BspModuleSupport(val m: BspModule) {
      def buildTargetId: BuildTargetIdentifier = bspIdByModule(m)
    }
  }

  private[this] var statePromise: Promise[State] = Promise[State]()
  initialEvaluator.foreach(e => statePromise.success(new State(e)))

//  private[this] def stateFuture: Future[State] = statePromise.future

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

//      if (request.getRootUri != bspIdByModule(millBuildTarget).getUri) {
//        log.debug(
//          s"Workspace root differs from mill build root! Requested root: ${request.getRootUri} Mill root: ${millBuildTarget.buildTargetId.getUri}"
//        )
//      }

//      val moduleBspInfo = bspModulesById.values.map(_.bspBuildTarget).toSeq

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
      capabilities.setCompileProvider(new CompileProvider(supportedLangs))
      capabilities.setRunProvider(new RunProvider(supportedLangs))
      capabilities.setTestProvider(new TestProvider(supportedLangs))
      capabilities.setDebugProvider(new DebugProvider(Seq().asJava))
      capabilities.setDependencySourcesProvider(true)

      capabilities.setDependencyModulesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setBuildTargetChangedProvider(
        false
      )
      capabilities.setCanReload(canReload)
      capabilities.setJvmRunEnvironmentProvider(true)
      capabilities.setJvmTestEnvironmentProvider(true)

      request.getDisplayName match {
        case "IntelliJ-BSP" => clientIsIntelliJ = true
        case _ => clientIsIntelliJ = false
      }

      request.getData match {
        case d: JsonObject =>
          log.debug(s"extra data: ${d} of type ${d.getClass}")
          if (d.has("semanticdbVersion")) {
            val semDb = d.get("semanticdbVersion")
            if (semDb.isJsonPrimitive) {
              val semDbVersion = semDb.getAsJsonPrimitive.getAsString
              log.debug(s"Got client semanticdbVersion: ${semDbVersion}. Enabling SemanticDB support.")
              clientWantsSemanticDb = true
            }
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
            val data = m.bspBuildTargetData()

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

    def sourceItem(source: Path, generated: Boolean) = {
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
        case (id, module: MillBuildTarget) if clientIsIntelliJ =>
          T.task {
            val sources = new SourcesItem(
              id,
              module.dummySources().map(p => sourceItem(p.path, true)).asJava
            )
            sources.setRoots(Seq(sanitizeUri(evaluator.rootModule.millSourcePath)).asJava)
            sources
          }
//        case (id, `millBuildTarget`) =>
//          T.task {
//            new SourcesItem(
//              id,
//              Seq(sourceItem(evaluator.rootModule.millSourcePath / "build.sc", false)).asJava
//            )
//          }
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

      val ids = Evaluator.evalOrThrow(evaluator)(tasks).flatten
      new InverseSourcesResult(ids.asJava)
    }
  }

  /**
   * External dependencies (sources or source jars).
   */
  override def buildTargetDependencySources(p: DependencySourcesParams)
      : CompletableFuture[DependencySourcesResult] =
    completable(hint = s"buildTargetDependencySources ${p}") { state: State =>
      import state._
      targetTasks(
        state,
        targetIds = p.getTargets.asScala.toSeq,
        agg = (items: Seq[DependencySourcesItem]) => new DependencySourcesResult(items.asJava)
      ) {
//        case (id, `millBuildTarget`) =>
//          T.task {
//            new DependencySourcesItem(
//              id,
//              ModuleUtils.getMillBuildClasspath(evaluator, sources = true).asJava
//            )
//          }
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

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    completable(s"buildTargetRun ${runParams}") { state =>
      import state._

      val params = TaskParameters.fromRunParams(runParams)
      val module = params.getTargets.map(bspModulesById).collectFirst {
        case m: JavaModule => m
      }.get
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args: _*)
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
      val millBuildTargetId = bspIdByModule(`mill-build`)

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
        .filter(_ != millBuildTargetId)
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

      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.filter(_ != `mill-build`.buildTargetId).foldLeft((
          "",
          true
        )) {
          case ((msg, cleaned), targetId) =>
            val module = bspModulesById(targetId)
            val mainModule = new MainModule {
              override implicit def millDiscover: Discover[_] = Discover[this.type]
            }
            val compileTargetName = (module.millModuleSegments ++ Segments(Label("compile"))).render
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
                module.millModuleSegments ++ Seq(Label("compile"))
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
    val res = Evaluator.evalOrThrow(evaluator)(tasks)
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
    def took =
      log.debug(s"${hint.split("[ ]").head} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()

    if (checkInitialized && !initialized) {
      future.completeExceptionally(
        new Exception("Can not respond to any request before receiving the `initialize` request.")
      )
    } else {
      statePromise.future.onComplete {
        case Success(state) =>
          try {
            val v = f(state)
            log.debug(s"${hint.split("[ ]").head} result: ${v}")
            took
            future.complete(v)
          } catch {
            case e: Exception =>
              logStream.println(s"Caught exception: ${e}")
              e.printStackTrace(logStream)
              took
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
    def took =
      log.debug(s"${hint.split("[ ]").head} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()

    if (checkInitialized && !initialized) {
      future.completeExceptionally(
        new Exception("Can not respond to any request before receiving the `initialize` request.")
      )
    } else {
      try {
        val v = f
        log.debug(s"${hint.split("[ ]").head} result: ${v}")
        took
        future.complete(v)
      } catch {
        case e: Exception =>
          logStream.println(s"Caugh exception: ${e}")
          e.printStackTrace(logStream)
          took
          future.completeExceptionally(e)
      }
    }

    future
  }
}
