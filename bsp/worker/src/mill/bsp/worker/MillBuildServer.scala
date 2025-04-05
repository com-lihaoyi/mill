package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.*
import com.google.gson.JsonObject
import mill.runner.api.{JvmBuildTarget, ScalaBuildTarget, *}
import mill.bsp.{Constants}
import mill.bsp.worker.Utils.{makeBuildTarget, outputPaths, sanitizeUri}
import mill.runner.api.Segment.Label
import mill.given

import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import scala.collection.mutable
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
private class MillBuildServer(
    topLevelProjectRoot: os.Path,
    bspVersion: String,
    serverVersion: String,
    serverName: String,
    logStream: PrintStream,
    canReload: Boolean,
    debugMessages: Boolean
) extends BuildServer {

  import MillBuildServer._

  private[worker] var cancellator: Boolean => Unit = _ => ()
  private[worker] var onSessionEnd: Option[BspServerResult => Unit] = None
  protected var client: BuildClient = scala.compiletime.uninitialized
  private var initialized = false
  private var shutdownRequested = false
  protected var clientWantsSemanticDb = false
  protected var clientIsIntelliJ = false

  /** `true` when client and server support the `JvmCompileClasspathProvider` request. */
  protected var enableJvmCompileClasspathProvider = false

  private var statePromise: Promise[State] = Promise[State]()

  def updateEvaluator(evaluatorsOpt: Option[Seq[EvaluatorApi]]): Unit = {
    debug(s"Updating Evaluator: $evaluatorsOpt")
    if (statePromise.isCompleted) statePromise = Promise[State]() // replace the promise
    evaluatorsOpt.foreach { evaluators =>
      statePromise.success(
        new State(topLevelProjectRoot, evaluators, s => debug(s()))
      )
    }
  }

  def print(msg: String): Unit =
    logStream.println(msg)
  def debug(msg: => String): Unit =
    if (debugMessages)
      logStream.println("[debug] " + msg)

  def onConnectWithClient(buildClient: BuildClient): Unit = client = buildClient

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    completableNoState(s"buildInitialize ${request}", checkInitialized = false) {

      val clientCapabilities = request.getCapabilities()
      enableJvmCompileClasspathProvider = clientCapabilities.getJvmCompileClasspathReceiver

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
      capabilities.setJvmCompileClasspathProvider(enableJvmCompileClasspathProvider)
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
            print(
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

      initialized = true
      new InitializeBuildResult(serverName, serverVersion, bspVersion, capabilities)
    }

  override def onBuildInitialized(): Unit = {
    print("Build initialized")
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    print("Entered buildShutdown")
    shutdownRequested = true
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        print("Shutdown build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModuleApi.resetContext()
    CompletableFuture.completedFuture(null.asInstanceOf[Object])
  }
  override def onBuildExit(): Unit = {
    print("Entered onBuildExit")
    onSessionEnd match {
      case None =>
      case Some(onEnd) =>
        print("Exiting build...")
        onEnd(BspServerResult.Shutdown)
    }
    SemanticDbJavaModuleApi.resetContext()
    cancellator(shutdownRequested)
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completableTasksWithState(
      "workspaceBuildTargets",
      targetIds = _.bspModulesIdList.map(_._1),
      tasks = { case m: BspModuleApi => m.bspBuildTargetData }
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
          debug(s"Unsupported dataKind=${dataKind} with value=${d}")
          None // unsupported data kind
        case None => None
      }

      val bt = m.bspBuildTarget
      makeBuildTarget(id, depsIds, bt, data)

    } { (targets, state) =>
      new WorkspaceBuildTargetsResult(
        (targets.asScala ++ state.syntheticRootBspBuildTarget.map(_.target)).asJava
      )
    }

  override def workspaceReload(): CompletableFuture[Object] =
    completableNoState("workspaceReload", false) {
      // Instead stop and restart the command
      // BSP.install(evaluator)
      onSessionEnd match {
        case None => "unsupportedWorkspaceReload".asInstanceOf[Object]
        case Some(onEnd) =>
          print("Reloading workspace...")
          onEnd(BspServerResult.ReloadWorkspace).asInstanceOf[Object]
      }
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

    completableTasksWithState(
      hint = s"buildTargetSources ${sourcesParams}",
      targetIds = _ => sourcesParams.getTargets.asScala.toSeq,
      tasks = {
        case module: JavaModuleApi =>
          Task.Anon {
            module.sources().map(p => sourceItem(p.path, false)) ++
              module.generatedSources().map(p => sourceItem(p.path, true))
          }
      }
    ) {
      case (ev, state, id, module, items) => new SourcesItem(id, items.asJava)
    } { (sourceItems, state) =>
      new SourcesResult(
        (sourceItems.asScala.toSeq ++ state.syntheticRootBspBuildTarget.map(_.synthSources)).asJava
      )
    }

  }

  override def buildTargetInverseSources(p: InverseSourcesParams)
      : CompletableFuture[InverseSourcesResult] = {
    completable(s"buildtargetInverseSources ${p}") { state =>
      val tasksEvaluators = state.bspModulesIdList.iterator.collect {
        case (id, (m: JavaModuleApi, ev)) =>
          Task.Anon {
            val src = m.allSourceFiles()
            val found = src.map(sanitizeUri).contains(
              p.getTextDocument.getUri
            )
            if (found) Seq(id) else Seq()
          } -> ev
      }.toSeq

      val ids = groupList(tasksEvaluators)(_._2)(_._1)
        .flatMap { case (ev, ts) => ev.execute(ts).values.get }
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
    completableTasks(
      hint = s"buildTargetDependencySources ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModuleApi =>
          Task.Anon {
            (
              m.millResolver().classpath(
                Seq(
                  m.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
                  m.coursierDependency
                ),
                sources = true
              ),
              m.unmanagedClasspath(),
              m.allRepositories()
            )
          }
      }
    ) {
      case (ev, state, id, m: JavaModuleApi, (resolveDepsSources, unmanagedClasspath, repos)) =>
        val buildSources =
          mill.scalalib.Lib
            .resolveMillBuildDeps(repos, None, useSources = true)
            .map(sanitizeUri(_))

        val cp = (resolveDepsSources ++ unmanagedClasspath).map(sanitizeUri).toSeq ++ buildSources
        new DependencySourcesItem(id, cp.asJava)
      case _ => ???
    } {
      new DependencySourcesResult(_)
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
    completableTasks(
      hint = "buildTargetDependencyModules",
      targetIds = _ => params.getTargets.asScala.toSeq,
      tasks = { case m: JavaModuleApi =>
        Task.Anon {
          (
            // full list of dependencies, including transitive ones
            m.millResolver()
              .resolution(
                Seq(
                  m.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
                  m.coursierDependency
                )
              )
              .orderedDependencies,
            m.unmanagedClasspath()
          )
        }
      }
    ) {
      case (
            ev,
            state,
            id,
            m: JavaModuleApi,
            (ivyDeps, unmanagedClasspath)
          ) =>
        val deps = ivyDeps.collect {
          case dep if dep.module.organization != JavaModule.internalOrg =>
            // TODO: add data with "maven" data kind using a ...
//          MavenDependencyModule

            new DependencyModule(dep.module.repr, dep.version)
        }
        val unmanaged = unmanagedClasspath.map { dep =>
          new DependencyModule(s"unmanaged-${dep.path.last}", "")
        }
        new DependencyModulesItem(id, (deps ++ unmanaged).iterator.toSeq.asJava)
      case _ => ???
    } {
      new DependencyModulesResult(_)
    }

  override def buildTargetResources(p: ResourcesParams): CompletableFuture[ResourcesResult] =
    completableTasks(
      s"buildTargetResources ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModuleApi => Task.Anon { m.resources() }
        case _ => Task.Anon { Nil }
      }
    ) {
      case (ev, state, id, m, resources) =>
        val resourcesUrls = resources.map(_.path).filter(os.exists).map(sanitizeUri)
        new ResourcesItem(id, resourcesUrls.asJava)

    } {
      new ResourcesResult(_)
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(p: CompileParams): CompletableFuture[CompileResult] =
    completable(s"buildTargetCompile ${p}") { state =>
      p.setTargets(state.filterNonSynthetic(p.getTargets))
      val params = TaskParameters.fromCompileParams(p)
      val taskId = params.hashCode()
      val compileTasksEvs = params.getTargets.distinct.map(state.bspModulesById).map {
        case (m: SemanticDbJavaModuleApi, ev) if clientWantsSemanticDb =>
          (m.compiledClassesAndSemanticDbFiles, ev)
        case (m: JavaModuleApi, ev) => (m.compile, ev)
        case (m: TestModuleApi, ev) =>
          (Task.Anon { () }, ev)
          val task = Task.Anon {
            Task.log.debug(
              s"Ignoring invalid compile request for test module ${m.bspBuildTarget.displayName}"
            )
          }
          (task, ev)
        case (m, ev) => Task.Anon {
            Result.Failure(
              s"Don't know how to compile non-Java target ${m.bspBuildTarget.displayName}"
            )
          } -> ev
      }

      val result = compileTasksEvs
        .groupMap(_._2)(_._1)
        .map { case (ev, ts) =>
          evaluate(
            ev,
            ts,
            Utils.getBspLoggedReporterPool(p.getOriginId, state.bspIdByModule, client),
            DummyTestReporter,
            new MillBspLogger(client, taskId, ev.baseLogger)
          )
        }
        .toSeq
      val compileResult = new CompileResult(Utils.getStatusCode(result))
      compileResult.setOriginId(p.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetOutputPaths(params: OutputPathsParams)
      : CompletableFuture[OutputPathsResult] =
    completable(s"buildTargetOutputPaths ${params}") { state =>
      val synthOutpaths = for {
        synthTarget <- state.syntheticRootBspBuildTarget
        if params.getTargets.contains(synthTarget.id)
        baseDir <- synthTarget.bt.baseDirectory
      } yield new OutputPathsItem(synthTarget.id, outputPaths(baseDir, topLevelProjectRoot).asJava)

      val items = for {
        target <- params.getTargets.asScala
        (module, ev) <- state.bspModulesById.get(target)
      } yield {
        val items =
          if (module.moduleCtx.external)
            outputPaths(
              module.bspBuildTarget.baseDirectory.get,
              topLevelProjectRoot
            )
          else
            outputPaths(module.bspBuildTarget.baseDirectory.get, topLevelProjectRoot)

        new OutputPathsItem(target, items.asJava)
      }

      new OutputPathsResult((items ++ synthOutpaths).asJava)
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    completable(s"buildTargetRun ${runParams}") { state =>
      val params = TaskParameters.fromRunParams(runParams)
      val (module, ev) = params.getTargets.map(state.bspModulesById).collectFirst {
        case (m: JavaModuleApi, ev) => (m, ev)
      }.get

      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(Task.Anon(Args(args)))
      val runResult = evaluate(
        ev,
        Seq(runTask),
        Utils.getBspLoggedReporterPool(runParams.getOriginId, state.bspIdByModule, client),
        logger = new MillBspLogger(client, runTask.hashCode(), ev.baseLogger)
      )
      val response = runResult.transitiveResults(runTask) match {
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
    completable(s"buildTargetTest ${testParams}") { state =>
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
                Seq(testTask),
                Utils.getBspLoggedReporterPool(
                  testParams.getOriginId,
                  state.bspIdByModule,
                  client
                ),
                testReporter,
                new MillBspLogger(client, testTask.hashCode, ev.baseLogger)
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
    completable(s"buildTargetCleanCache ${cleanCacheParams}") { state =>
      cleanCacheParams.setTargets(state.filterNonSynthetic(cleanCacheParams.getTargets))
      val (msg, cleaned) = ("", true)
//        cleanCacheParams.getTargets.asScala.foldLeft((
//          "",
//          true
//        )) {
//          case ((msg, cleaned), targetId) =>
//            val (module, ev) = state.bspModulesById(targetId)
//            val mainModule = new mill.define.BaseModule(topLevelProjectRoot) with MainModule {
//              override implicit def millDiscover: Discover = Discover[this.type]
//            }
//            val compileTargetName = (module.moduleSegments ++ Label("compile")).render
//            debug(s"about to clean: ${compileTargetName}")
//            val cleanTask = mainModule.clean(ev, Seq(compileTargetName)*)
//            val cleanResult = evaluate(
//              ev,
//              Seq(cleanTask),
//              logger = new MillBspLogger(client, cleanTask.hashCode, ev.baseLogger)
//            )
//            if (cleanResult.transitiveFailing.size > 0) (
//              msg + s" Target ${compileTargetName} could not be cleaned. See message from mill: \n" +
//                (cleanResult.transitiveResults(cleanTask) match {
//                  case ex: ExecResult.Exception => ex.toString()
//                  case ExecResult.Skipped => "Task was skipped"
//                  case ExecResult.Aborted => "Task was aborted"
//                  case _ => "could not retrieve the failure message"
//                }),
//              false
//            )
//            else {
//              val outPaths = mill.define.ExecutionPaths.resolve(
//                ev.outPath,
//                module.moduleSegments ++ Label("compile")
//              )
//              val outPathSeq = Seq(outPaths.dest, outPaths.meta, outPaths.log)
//
//              while (outPathSeq.exists(os.exists(_))) Thread.sleep(10)
//
//              (msg + s"${module.bspBuildTarget.displayName} cleaned \n", cleaned)
//            }
//        }

      new CleanCacheResult(cleaned).tap { it =>
        it.setMessage(msg)
      }
    }

  override def debugSessionStart(debugParams: DebugSessionParams)
      : CompletableFuture[DebugSessionAddress] =
    completable(s"debugSessionStart ${debugParams}") { state =>
      debugParams.setTargets(state.filterNonSynthetic(debugParams.getTargets))
      throw new NotImplementedError("debugSessionStart endpoint is not implemented")
    }

  /**
   * @params tasks A partial function
   * @param f The function must accept the same modules as the partial function given by `tasks`.
   */
  def completableTasks[T, V, W: ClassTag](
      hint: String,
      targetIds: State => Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]]
  )(f: (EvaluatorApi, State, BuildTargetIdentifier, BspModuleApi, W) => T)(agg: java.util.List[T] => V)
      : CompletableFuture[V] =
    completableTasksWithState[T, V, W](hint, targetIds, tasks)(f)((l, _) => agg(l))

  /**
   * @params tasks A partial function
   * @param f The function must accept the same modules as the partial function given by `tasks`.
   */
  def completableTasksWithState[T, V, W: ClassTag](
      hint: String,
      targetIds: State => Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]]
  )(f: (EvaluatorApi, State, BuildTargetIdentifier, BspModuleApi, W) => T)(agg: (
      java.util.List[T],
      State
  ) => V): CompletableFuture[V] = {
    val prefix = hint.split(" ").head
    completable(hint) { (state: State) =>
      val ids = state.filterNonSynthetic(targetIds(state).asJava).asScala
      val tasksSeq = ids.flatMap { id =>
        val (m, ev) = state.bspModulesById(id)
        tasks.lift.apply(m).map(ts => (ts, (ev, id)))
      }

      // group by evaluator (different root module)
      val groups0 = groupList(tasksSeq.toSeq)(_._2._1) {
        case (tasks, (_, id)) => (id, tasks)
      }

      val evaluated = groups0.flatMap {
        case (ev, targetIdTasks) =>
          val results = evaluate(ev, targetIdTasks.map(_._2))
          val idByTasks = targetIdTasks.map { case (id, task) => (task: Task[_], id) }.toMap
          val failures = results.transitiveResults.toSeq.collect {
            case (task, res: ExecResult.Failing[_]) if idByTasks.contains(task) =>
              (idByTasks(task), res)
          }

          def logError(id: BuildTargetIdentifier, errorMsg: String): Unit = {
            val msg = s"Request '$prefix' failed for ${id.getUri}: ${errorMsg}"
            debug(msg)
            client.onBuildLogMessage(new LogMessageParams(MessageType.ERROR, msg))
          }

          if (failures.nonEmpty)
            for ((id, failure) <- failures)
              logError(id, failure.toString)

          val resultsById = targetIdTasks.flatMap {
            case (id, task) =>
              results.transitiveResults(task)
                .asSuccess
                .map(_.value.value.asInstanceOf[W])
                .map((id, _))
          }

          resultsById.flatMap {
            case (id, values) =>
              try {
                Seq(f(ev, state, id, state.bspModulesById(id)._1, values))
              } catch {
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
    print(s"Entered ${hint}")

    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      print(s"${prefix} took ${System.currentTimeMillis() - start} msec")

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
            requestLock.lock()
            val v = os.checker.withValue(os.Checker.Nop)(f(state))
            took
            debug(s"${prefix} result: ${v}")
            future.complete(v)
          } catch {
            case e: Exception =>
              took
              logStream.println(s"${prefix} caught exception: ${e}")
              e.printStackTrace(logStream)
              future.completeExceptionally(e)
          } finally {
            requestLock.unlock()
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
    print(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    val prefix = hint.split(" ").head
    def took =
      print(s"${prefix} took ${System.currentTimeMillis() - start} msec")

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

  override def onRunReadStdin(params: ReadParams): Unit = {
    debug("onRunReadStdin is current unsupported")
  }

  private def evaluate(
      evaluator: EvaluatorApi,
      goals: Seq[TaskApi[?]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: Logger = null
  ): ExecutionResults = {
    val logger0 = Option(logger).getOrElse(evaluator.baseLogger)
    mill.runner.MillMain.withOutLock(
      noBuildLock = false,
      noWaitForBuildLock = false,
      out = evaluator.outPath,
      targetsAndParams = goals.toSeq.map {
        case n: NamedTask[_] => n.label
        case t => t.toString
      },
      streams = logger0.streams
    ) {
      evaluator.execute(
        goals,
        reporter,
        testReporter,
        logger0,
        serialCommandExec = false
      ).executionResults
    }
  }
}

private object MillBuildServer {

  /**
   * Same as Iterable.groupMap, but returns a sequence instead of a map, and preserves
   * the order of appearance of the keys from the input sequence
   */
  private def groupList[A, K, B](seq: Seq[A])(key: A => K)(f: A => B): Seq[(K, Seq[B])] = {
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
      .map {
        case (k, l) =>
          (k, l.result())
      }
      .toList
  }

  def jvmBuildTarget(d: JvmBuildTarget): bsp4j.JvmBuildTarget =
    new bsp4j.JvmBuildTarget().tap { it =>
      d.javaHome.foreach(jh => it.setJavaHome(jh.uri))
      d.javaVersion.foreach(jv => it.setJavaVersion(jv))
    }
}
