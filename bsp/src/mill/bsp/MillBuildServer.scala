package mill.bsp

import ch.epfl.scala.bsp4j._
import com.google.gson.JsonObject

import java.util.concurrent.CompletableFuture
import mill._
import mill.api.{Ctx, DummyTestReporter, Logger, Loose, Result, Strict}
import mill.bsp.ModuleUtils._
import mill.bsp.Utils._
import mill.define.Segment.Label
import mill.define.{BaseModule, Discover, ExternalModule, Task}
import mill.eval.Evaluator
import mill.main.{EvaluatorScopt, MainModule}
import mill.modules.Jvm
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import mill.scalalib.bsp.{BspBuildTargetId, BspModule, BspUri, MillBuildTarget}
import mill.util.{ColorLogger, DummyLogger, PrintLogger}
import os.Path

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

class MillBuildServer(
    evaluator: Evaluator,
    bspVersion: String,
    serverVersion: String,
    serverName: String
) extends ExternalModule
    with BuildServer
    with ScalaBuildServer
    with JavaBuildServer {
  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]
  implicit val ctx: Ctx.Log with Ctx.Home = new Ctx.Log with Ctx.Home {
    val log: Logger = evaluator.baseLogger match {
      case PrintLogger(c1, d, c2, _, i, e, in, de, uc) =>
        // Map all output to debug channel
        PrintLogger(c1, d, c2, e, i, e, in, de, uc)
      case l => l
    }
    val home: Path = evaluator.rootModule.millSourcePath
  }
  var cancellator: Boolean => Unit = shutdownBefore => ()
  var client: BuildClient = _
  var initialized = false
  var clientInitialized = false
  var shutdownRequested = false

  object log {
    def debug(msg: String) = ctx.log.errorStream.println(msg)
  }

  object sanitizeUri {
    def apply(uri: String): String =
      if (uri.endsWith("/")) apply(uri.substring(0, uri.length - 1)) else uri
    def apply(uri: os.Path): String = apply(uri.toNIO.toUri.toString)
    def apply(uri: PathRef): String = apply(uri.path)
  }

  private[this] object internal {
    def clear(): Unit = synchronized {
      idToModule = None
      modulesToId = None
    }
    def init(): Unit = synchronized {
      idToModule match {
        case None =>
          val modules: Seq[Module] =
            evaluator.rootModule.millInternal.modules ++ Seq(millBuildTarget)
          val map = modules.collect {
            case m: MillBuildTarget =>
              val uri = sanitizeUri(m.millSourcePath)
              val id = new BuildTargetIdentifier(uri)
              (id, m)
            case m: BspModule =>
              val uri = sanitizeUri(millBuildTarget.millSourcePath / m.millModuleSegments.parts)
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

  private lazy val millBuildTarget = new MillBuildTarget(evaluator.rootModule)
  private def bspModulesById: Map[BuildTargetIdentifier, BspModule] = {
    internal.init()
    internal.idToModule.get
  }
  private def bspIdByModule: Map[BspModule, BuildTargetIdentifier] = {
    internal.init()
    internal.modulesToId.get
  }

  override def onConnectWithClient(server: BuildClient): Unit = client = server

  override def buildInitialize(request: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    completable(s"buildInitialize ${request}", checkInitialized = false) {
      // TODO: scan BspModules and infer their capabilities

      if (request.getRootUri != bspIdByModule(millBuildTarget).getUri) {
        log.debug(
          s"Workspace root differs from mill build root! Requested root: ${request.getRootUri} Mill root: ${millBuildTarget.buildTargetId.getUri}"
        )
      }

      val moduleBspInfo = bspModulesById.values.map(_.bspBuildTarget).toSeq

      val clientCaps = request.getCapabilities().getLanguageIds().asScala

      val compileLangs = moduleBspInfo.filter(_.canCompile).flatMap(_.languageIds).distinct.filter(
        clientCaps.contains
      )
      val runLangs =
        moduleBspInfo.filter(_.canRun).flatMap(
          _.languageIds
        ).distinct // .filter(clientCaps.contains)
      val testLangs =
        moduleBspInfo.filter(_.canTest).flatMap(
          _.languageIds
        ).distinct //.filter(clientCaps.contains)
      val debugLangs =
        moduleBspInfo.filter(_.canDebug).flatMap(
          _.languageIds
        ).distinct //.filter(clientCaps.contains)

      val capabilities = new BuildServerCapabilities
      capabilities.setCompileProvider(new CompileProvider(compileLangs.asJava))
      capabilities.setRunProvider(new RunProvider(runLangs.asJava))
      capabilities.setTestProvider(new TestProvider(testLangs.asJava))
      capabilities.setDebugProvider(new DebugProvider(debugLangs.asJava))
      capabilities.setDependencySourcesProvider(true)

      capabilities.setDependencyModulesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setBuildTargetChangedProvider(
        false
      )
      //TODO: for now it's false, but will try to support this later
      capabilities.setCanReload(false)

      initialized = true
      new InitializeBuildResult("mill-bsp", serverVersion, bspVersion, capabilities)
    }

  override def onBuildInitialized(): Unit = {
    clientInitialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] =
    completable("buildShutdown") {
      shutdownRequested = true
      null.asInstanceOf[Object]
    }

  override def onBuildExit(): Unit = {
    log.debug("onBuildExit")
    cancellator(shutdownRequested)
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completableTasks(
      "workspaceBuildTargets",
      targetIds = bspModulesById.keySet.toSeq,
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

  override def workspaceReload(): CompletableFuture[Object] =
    completable("workspaceReload") {
      // Instead stop and restart the command
      // BSP.install(evaluator)
      null.asInstanceOf[Object]
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

    completableTasks(
      hint = s"buildTargetSources ${sourcesParams}",
      targetIds = sourcesParams.getTargets.asScala.toSeq,
      agg = (items: Seq[SourcesItem]) => new SourcesResult(items.asJava)
    ) {
      case (id, `millBuildTarget`) =>
        T.task {
          new SourcesItem(
            id,
            Seq(sourceItem(evaluator.rootModule.millSourcePath / "build.sc", false)).asJava
          )
        }
      case (id, module: JavaModule) =>
        T.task {
          val items = module.sources().map(p => sourceItem(p.path, false)) ++
            module.generatedSources().map(p => sourceItem(p.path, true))
          new SourcesItem(id, items.asJava)
        }
    }
  }

  override def buildTargetInverseSources(p: InverseSourcesParams)
      : CompletableFuture[InverseSourcesResult] = {
    completable(s"buildtargetInverseSources ${p}") {
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
    completableTasks(
      hint = s"buildTargetDependencySources ${p}",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[DependencySourcesItem]) => new DependencySourcesResult(items.asJava)
    ) {
      case (id, `millBuildTarget`) =>
        T.task {
          new DependencySourcesItem(id, getMillBuildClasspath(evaluator, sources = true).asJava)
        }
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
  override def buildTargetCompile(compileParams: CompileParams): CompletableFuture[CompileResult] =
    completable(s"buildTargetCompile ${compileParams}") {
      val modules = bspModulesById.values.toSeq.collect { case m: JavaModule => m }

      val params = TaskParameters.fromCompileParams(compileParams)
      val taskId = params.hashCode()
      val compileTasks = params.getTargets.map(bspModulesById).map {
        case m: JavaModule => m.compile
        case m => T.task {
            Result.Failure(
              s"Don't know now to compile non-Java target ${m.bspBuildTarget.displayName}"
            )
          }
      }
      val result = evaluator.evaluate(
        compileTasks,
        getBspLoggedReporterPool(compileParams.getOriginId, modules, evaluator, client),
        DummyTestReporter,
        new MillBspLogger(client, taskId, evaluator.baseLogger)
      )
      val compileResult = new CompileResult(getStatusCode(result))
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    completable(s"buildTargetRun ${runParams}") {
      val modules = bspModulesById.values.toSeq.collect {
        case m: JavaModule => m
      }

      val params = TaskParameters.fromRunParams(runParams)
      val module = params.getTargets.map(bspModulesById).collectFirst {
        case m: JavaModule => m
      }.get
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args: _*)
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        getBspLoggedReporterPool(runParams.getOriginId, modules, evaluator, client),
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
    completable(s"buildTargetTest ${testParams}") {
      val modules = bspModulesById.values.toSeq.collect { case m: JavaModule => m }
      val millBuildTargetId = bspIdByModule(millBuildTarget)

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
                getBspLoggedReporterPool(testParams.getOriginId, modules, evaluator, client),
                testReporter,
                new MillBspLogger(client, testTask.hashCode, evaluator.baseLogger)
              )
              val statusCode = getStatusCode(results)

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
    completable(s"buildTargetCleanCache ${cleanCacheParams}") {
      val modules = bspModulesById.values.toSeq.collect { case m: JavaModule => m }
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.filter(_ != millBuildTargetId).foldLeft(("", true)) {
          case ((msg, cleaned), targetId) =>
            val module = bspModulesById(targetId)
            val mainModule = new MainModule {
              override implicit def millDiscover: Discover[_] = Discover[this.type]
            }
            val cleanTask =
              mainModule.clean(evaluator, Seq(s"${module.bspBuildTarget.displayName}.compile"): _*)
            val cleanResult = evaluator.evaluate(
              Strict.Agg(cleanTask),
              logger = new MillBspLogger(client, cleanTask.hashCode, evaluator.baseLogger)
            )
            if (cleanResult.failing.keyCount > 0) (
              msg + s" Target ${module.bspBuildTarget.displayName} could not be cleaned. See message from mill: \n" +
                (cleanResult.results(cleanTask) match {
                  case fail: Result.Failure[Any] => fail.msg + "\n"
                  case _ => "could not retrieve message"
                }),
              false
            )
            else {
              val outDir = Evaluator
                .resolveDestPaths(
                  evaluator.outPath,
                  module.millModuleSegments ++ Seq(Label("compile"))
                )
                .out
              while (os.exists(outDir)) Thread.sleep(10)

              (msg + s"${module.bspBuildTarget.displayName} cleaned \n", cleaned)
            }
        }

      new CleanCacheResult(msg, cleaned)
    }

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completableTasks(
      s"buildTargetJavacOptions ${javacOptionsParams}",
      targetIds = javacOptionsParams.getTargets.asScala.toSeq,
      agg = (items: Seq[JavacOptionsItem]) => new JavacOptionsResult(items.asJava)
    ) {
      case (id, `millBuildTarget`) => T.task {
          val classpath = getMillBuildClasspath(evaluator, sources = false)
          new JavacOptionsItem(
            id,
            Seq.empty.asJava,
            classpath.iterator.toSeq.asJava,
            sanitizeUri(evaluator.outPath)
          )
        }
      case (id, m: JavaModule) => T.task {
          val options = m.javacOptions()
          val classpath = m.compileClasspath().map(sanitizeUri.apply)
          new JavacOptionsItem(
            id,
            options.asJava,
            classpath.iterator.toSeq.asJava,
            sanitizeUri(m.bspCompileClassesPath(T.task{evaluator})())
          )
        }
    }

  def completableTasks[T: ClassTag, V](
      hint: String,
      targetIds: Seq[BuildTargetIdentifier],
      agg: Seq[T] => V
  )(f: (BuildTargetIdentifier, BspModule) => Task[T]): CompletableFuture[V] =
    completable(hint) {
      val tasks: Seq[Task[T]] = targetIds.map(id => f(id, bspModulesById(id)))
      val res = Evaluator.evalOrThrow(evaluator)(tasks)
      agg(res)
    }

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    completableTasks(
      hint = s"buildTargetScalacOptions ${p}",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalacOptionsItem]) => new ScalacOptionsResult(items.asJava)
    ) {
      case (id, `millBuildTarget`) => T.task {
          new ScalacOptionsItem(
            id,
            Seq.empty[String].asJava,
            getMillBuildClasspath(evaluator, false).asJava,
            sanitizeUri(evaluator.outPath)
          )
        }
      case (id, m: JavaModule) =>
        val optionsTask = m match {
          case sm: ScalaModule => sm.scalacOptions
          case _ => T.task { Seq.empty[String] }
        }

        val teval = T.task(evaluator)
        T.task {
          new ScalacOptionsItem(
            id,
            optionsTask().asJava,
            m.bspCompileClasspath(teval)().map(sanitizeUri.apply).iterator.toSeq.asJava,
            sanitizeUri(m.bspCompileClassesPath(teval)())
          )
        }
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    completableTasks(
      hint = "buildTragetScalaMainClasses",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalaMainClassesItem]) => new ScalaMainClassesResult(items.asJava)
    ) {
      case (id, m: JavaModule) =>
        T.task {
          // We find all main classes, although we could also find only the configured one
          val mainClasses = m.zincWorker.worker().discoverMainClasses(m.compile())
          // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
          val jvmOpts = m.forkArgs()
          val envs = m.forkEnv()
          val items = mainClasses.map(mc =>
            new ScalaMainClass(mc, Seq().asJava, jvmOpts.asJava).tap {
              _.setEnvironmentVariables(envs.map(e => s"${e._1}=${e._2}").toSeq.asJava)
            }
          )
          new ScalaMainClassesItem(id, items.asJava)
        }
      case (id, _) => T.task {
          // no Java module, so no main classes
          new ScalaMainClassesItem(id, Seq.empty[ScalaMainClass].asJava)
        }

    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    completableTasks(
      s"buildTargetScalaTestClasses ${p}",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalaTestClassesItem]) => new ScalaTestClassesResult(items.asJava)
    ) {
      case (id, m: TestModule) => T.task {
          val classpath = m.runClasspath()
          val testFramework = m.testFramework()
          val compResult = m.compile()

          val classFingerprint = Jvm.inprocess(
            classpath.map(_.path),
            classLoaderOverrideSbtTesting = true,
            isolated = true,
            closeContextClassLoaderWhenDone = false,
            cl => {
              val framework = TestRunner.framework(testFramework)(cl)
              Lib.discoverTests(
                cl,
                framework,
                Agg(compResult.classes.path)
              )
            }
          )
          Seq.from(classFingerprint.map(classF => classF._1.getName.stripSuffix("$")))

          val classes = Seq.empty[String]
          new ScalaTestClassesItem(id, classes.asJava)
        }
      case (id, _) => T.task {
          // Not a test module, so no test classes
          new ScalaTestClassesItem(id, Seq.empty[String].asJava)
        }
    }

  /**
   * Given a function that take input of type T and return output of type V,
   * apply the function on the given inputs and return a completable future of
   * the result. If the execution of the function raises an Exception, complete
   * the future exceptionally. Also complete exceptionally if the server was not
   * yet initialized.
   */
  private[this] def completable[V](
      hint: String,
      checkInitialized: Boolean = true
  )(f: => V): CompletableFuture[V] = {
    log.debug(s"Entered ${hint}")
    val start = System.currentTimeMillis()
    def took =
      log.debug(s"${hint.split("[ ]").head} took ${System.currentTimeMillis() - start} msec")

    val future = new CompletableFuture[V]()
    if (!checkInitialized || initialized) {
      try {
        val v = f
        log.debug(s"${hint.split("[ ]").head} result: ${v}")
        took
        future.complete(v)
      } catch {
        case e: Exception =>
          ctx.log.errorStream.println(s"Caugh exception: ${e}")
          e.printStackTrace(ctx.log.errorStream)
          took
          future.completeExceptionally(e)
      }
    } else {
      future.completeExceptionally(
        new Exception("Can not respond to any request before receiving the `initialize` request.")
      )
    }
    future
  }

  /** Convert to BSP API. */
  implicit class BspModuleSupport(val m: BspModule) {

    def buildTargetId: BuildTargetIdentifier = bspIdByModule(m)

  }

}
