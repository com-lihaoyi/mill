package mill.bsp

import ch.epfl.scala.bsp4j._
import com.google.gson.JsonObject

import java.util.concurrent.CompletableFuture
import mill._
import mill.api.{Ctx, DummyTestReporter, Logger, Result, Strict}
import mill.bsp.ModuleUtils._
import mill.bsp.Utils._
import mill.define.Segment.Label
import mill.define.{BaseModule, Discover, ExternalModule, Task}
import mill.eval.Evaluator
import mill.main.{EvaluatorScopt, MainModule}
import mill.scalalib._
import mill.scalalib.bsp.{BspBuildTargetId, BspModule, BspUri, MillBuildTarget}
import mill.util.{DummyLogger, PrintLogger}
import os.Path

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

class MillBuildServer(
    evaluator: Evaluator,
    bspVersion: String,
    serverVersion: String
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
  var cancelator: () => Unit = () => ()
  var client: BuildClient = _
  var initialized = false
  var clientInitialized = false

  object log {
    def debug(msg: String) = ctx.log.errorStream.println(msg)
  }

  override def onConnectWithClient(server: BuildClient): Unit = client = server

  override def buildInitialize(params: InitializeBuildParams)
      : CompletableFuture[InitializeBuildResult] =
    completable(s"buildInitialize ${params}", checkInitialized = false) {
      // TODO: scan BspModules and infer their capabilities

      val modules = bspModulesById.values
      val moduleBspInfo = modules.map(_.bspBuildTarget).toSeq
      val clientCaps = params.getCapabilities().getLanguageIds().asScala
      val compileLangs = moduleBspInfo.filter(_.canCompile).flatMap(_.languageIds).distinct.filter(
        clientCaps.contains
      )
      val runLangs =
        moduleBspInfo.filter(_.canRun).flatMap(_.languageIds).distinct.filter(clientCaps.contains)
      val testLangs =
        moduleBspInfo.filter(_.canTest).flatMap(_.languageIds).distinct.filter(clientCaps.contains)
      val debugLangs =
        moduleBspInfo.filter(_.canDebug).flatMap(_.languageIds).distinct.filter(clientCaps.contains)

      val capabilities = new BuildServerCapabilities
      capabilities.setCompileProvider(new CompileProvider(compileLangs.asJava))
      capabilities.setRunProvider(new RunProvider(runLangs.asJava))
      capabilities.setTestProvider(new TestProvider(testLangs.asJava))
      capabilities.setDebugProvider(new DebugProvider(debugLangs.asJava))
      capabilities.setDependencySourcesProvider(true)
      // TODO: implement
      capabilities.setDependencyModulesProvider(false)
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
      "shut down this server".asInstanceOf[Object]
    }

  override def onBuildExit(): Unit = cancelator()

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
            case m: BspModule =>
              val uri =
                (millBuildTarget.millSourcePath / m.millModuleSegments.parts).toNIO.toUri.toString
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

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completable("workspaceBuildTargets") {
      // use bsp module
      log.debug(s"(new) Found ${bspModulesById.size} BspModules")
      val targets = bspModulesById.values.map(_.buildTarget)
      new WorkspaceBuildTargetsResult(targets.toList.asJava)
    }

  override def buildTargetSources(sourcesParams: SourcesParams)
      : CompletableFuture[SourcesResult] = {

    def sourceItem(source: Path, generated: Boolean) = {
      new SourceItem(
        source.toNIO.toUri.toString,
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
            val found = src.map(_.path.toNIO.toUri.toString).contains(
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
          val cp = (sources ++ unmanaged).map(_.path.toNIO.toUri.toString).iterator.toSeq
          new DependencySourcesItem(id, cp.asJava)
        }
    }

  override def buildTargetResources(resourcesParams: ResourcesParams)
      : CompletableFuture[ResourcesResult] =
    completable(s"buildTargetResources ${resourcesParams}") {
      val modules = getModules(evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val items = resourcesParams.getTargets.asScala
        .filter(_ != millBuildTargetId)
        .foldLeft(Seq.empty[ResourcesItem]) { (items, targetId) =>
          val resources = evaluateInformativeTask(
            evaluator,
            getModule(targetId, modules).resources,
            Seq.empty[PathRef]
          )
            .filter(pathRef => os.exists(pathRef.path))

          items :+ new ResourcesItem(targetId, resources.map(_.path.toNIO.toUri.toString).asJava)
        }

      new ResourcesResult(items.asJava)
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(compileParams: CompileParams): CompletableFuture[CompileResult] =
    completable(s"buildTargetCompile ${compileParams}") {
      val modules = getModules(evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val params = TaskParameters.fromCompileParams(compileParams)
      val taskId = params.hashCode()
      val compileTasks = Strict.Agg(
        params.getTargets.distinct.filter(_ != millBuildTargetId).map(
          getModule(_, modules).compile
        ): _*
      )
      val result = evaluator.evaluate(
        compileTasks,
        getBspLoggedReporterPool(params, modules, evaluator, client),
        DummyTestReporter,
        new MillBspLogger(client, taskId, evaluator.baseLogger)
      )
      val compileResult = new CompileResult(getStatusCode(result))
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    completable(s"buildTargetRun ${runParams}") {
      val modules = getModules(evaluator)

      val params = TaskParameters.fromRunParams(runParams)
      val module = getModule(params.getTargets.head, modules)
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args: _*)
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        getBspLoggedReporterPool(params, modules, evaluator, client),
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
      val modules = getModules(evaluator)
      val targets = getTargets(modules, evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

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
          getModule(targetId, modules) match {
            case m: TestModule =>
              val testModule = m.asInstanceOf[TestModule]
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
                getBspLoggedReporterPool(params, modules, evaluator, client),
                testReporter,
                new MillBspLogger(client, testTask.hashCode, evaluator.baseLogger)
              )
              val statusCode = getStatusCode(results)

              // Notifying the client that the testing of this build target ended
              val taskFinishParams =
                new TaskFinishParams(new TaskId(testTask.hashCode().toString), statusCode)
              taskFinishParams.setEventTime(System.currentTimeMillis())
              taskFinishParams.setMessage(
                "Finished testing target" + targets.find(_.getId == targetId).fold("")(t =>
                  s": ${t.getDisplayName}"
                )
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
      val modules = getModules(evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val (msg, cleaned) =
        cleanCacheParams.getTargets.asScala.filter(_ != millBuildTargetId).foldLeft(("", true)) {
          case ((msg, cleaned), targetId) =>
            val module = getModule(targetId, modules)
            val mainModule = new MainModule {
              override implicit def millDiscover: Discover[_] = Discover[this.type]
            }
            val cleanTask =
              mainModule.clean(evaluator, Seq(s"${module.millModuleSegments.render}.compile"): _*)
            val cleanResult = evaluator.evaluate(
              Strict.Agg(cleanTask),
              logger = new MillBspLogger(client, cleanTask.hashCode, evaluator.baseLogger)
            )
            if (cleanResult.failing.keyCount > 0) (
              msg + s" Target ${module.millModuleSegments.render} could not be cleaned. See message from mill: \n" +
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

              (msg + s"${module.millModuleSegments.render} cleaned \n", cleaned)
            }
        }

      new CleanCacheResult(msg, cleaned)
    }

  override def workspaceReload(): CompletableFuture[Object] =
    completable("workspaceReload") {
      // Instead stop and restart the command
      // BSP.install(evaluator)
      null.asInstanceOf[Object]
    }

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completable(s"buildTargetJavacOptions ${javacOptionsParams}") {
      val modules = getModules(evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val items = javacOptionsParams.getTargets.asScala
        .foldLeft(Seq.empty[JavacOptionsItem]) { (items, targetId) =>
          val newItem =
            if (targetId == millBuildTargetId) {
              val classpath = getMillBuildClasspath(evaluator, sources = false)
              Some(new JavacOptionsItem(
                targetId,
                Seq.empty.asJava,
                classpath.iterator.toSeq.asJava,
                evaluator.outPath.toNIO.toUri.toString
              ))
            } else
              getModule(targetId, modules) match {
                case m: JavaModule =>
                  val options =
                    evaluateInformativeTask(evaluator, m.javacOptions, Seq.empty[String]).toList
                  val classpath =
                    evaluateInformativeTask(evaluator, m.compileClasspath, Agg.empty[PathRef])
                      .map(_.path.toNIO.toUri.toString)
                  val classDirectory = (Evaluator
                    .resolveDestPaths(
                      evaluator.outPath,
                      m.millModuleSegments ++ Seq(Label("compile"))
                    )
                    .dest / "classes").toNIO.toUri.toString

                  Some(new JavacOptionsItem(
                    targetId,
                    options.asJava,
                    classpath.iterator.toSeq.asJava,
                    classDirectory
                  ))
                case _ => None
              }

          items ++ newItem
        }

      new JavacOptionsResult(items.asJava)
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

//  override def buildTargetScalacOptions(
//      scalacOptionsParams: ScalacOptionsParams
//  ): CompletableFuture[ScalacOptionsResult] =
//    completable(s"buildTargetScalacOptions ${scalacOptionsParams}") {
//      val ids = scalacOptionsParams.getTargets.asScala.toSeq
//
//      val tasks: Seq[Task[(BuildTargetIdentifier, Seq[String], Seq[String], String)]] =
//        ids.map(id => (id, bspModulesById(id))).collect {
//          case (id, `millBuildTarget`) =>
//            T.task {
//              (
//                id,
//                Seq.empty[String],
//                getMillBuildClasspath(evaluator, false),
//                evaluator.outPath.toNIO.toUri.toString
//              )
//            }
//          case (id, module: ScalaModule) =>
//            T.task {
//              (
//                id,
//                module.scalacOptions(),
//                module.compileClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq,
//                module.compile().classes.path.toNIO.toUri.toString
//              )
//            }
//        }
//
//      val results: Seq[(BuildTargetIdentifier, Seq[String], Seq[String], String)] =
//        Evaluator.evalOrThrow(evaluator)(tasks)
//
//      val items = results.map {
//        case (id, options, classpath, dest) =>
//          new ScalacOptionsItem(id, options.asJava, classpath.asJava, dest)
//      }
//
//      new ScalacOptionsResult(items.asJava)
//    }

  override def buildTargetScalacOptions(
      scalacOptionsParams: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] =
    completableTasks(
      hint = s"buildTargetScalacOptions ${scalacOptionsParams}",
      targetIds = scalacOptionsParams.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalacOptionsItem]) => new ScalacOptionsResult(items.asJava)
    ) {
      case (id, `millBuildTarget`) =>
        T.task {
          new ScalacOptionsItem(
            id,
            Seq.empty[String].asJava,
            getMillBuildClasspath(evaluator, false).asJava,
            evaluator.outPath.toNIO.toUri.toString
          )
        }
      case (id, module: ScalaModule) =>
        T.task {
          new ScalacOptionsItem(
            id,
            module.scalacOptions().asJava,
            module.compileClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq.asJava,
            module.compile().classes.path.toNIO.toUri.toString
          )
        }
    }

  // TODO: In the case when mill fails to provide a main classes because multiple were
  // defined for the same module, do something so that those can still be detected
  // such that IntelliJ can run any of them
  override def buildTargetScalaMainClasses(
      scalaMainClassesParams: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] =
    completable(s"buildTargetScalaMainClasses ${scalaMainClassesParams}") {
      val modules = getModules(evaluator)
      val millBuildTargetId = getMillBuildTargetId(evaluator)

      val items = scalaMainClassesParams.getTargets.asScala
        .filter(_ != millBuildTargetId)
        .foldLeft(Seq.empty[ScalaMainClassesItem]) { (items, targetId) =>
          val module = getModule(targetId, modules)
          val scalaMainClasses = getTaskResult(evaluator, module.finalMainClassOpt) match {
            case result: Result.Success[_] =>
              result.asSuccess.get.value match {
                case mainClass: Right[String, String] =>
                  Seq(
                    new ScalaMainClass(
                      mainClass.value,
                      Seq.empty[String].asJava,
                      evaluateInformativeTask(
                        evaluator,
                        module.forkArgs,
                        Seq.empty[String]
                      ).toList.asJava
                    )
                  )
                case msg: Left[String, String] =>
                  val messageParams = new ShowMessageParams(MessageType.WARNING, msg.value)
                  messageParams.setOriginId(scalaMainClassesParams.getOriginId)
                  client.onBuildShowMessage(
                    messageParams
                  ) // tell the client that no main class was found or specified
                  Seq.empty[ScalaMainClass]
              }
            case _ => Seq.empty[ScalaMainClass]
          }

          items :+ new ScalaMainClassesItem(targetId, scalaMainClasses.asJava)
        }

      new ScalaMainClassesResult(items.asJava)
    }

  override def buildTargetScalaTestClasses(
      scalaTestClassesParams: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] =
    completable(s"buildTargetScalaTestClasses ${scalaTestClassesParams}") {
      val modules = getModules(evaluator)

      val items =
        scalaTestClassesParams.getTargets.asScala.foldLeft(Seq.empty[ScalaTestClassesItem]) {
          (items, targetId) =>
            val newItem = getModule(targetId, modules) match {
              case module: TestModule =>
                Some(new ScalaTestClassesItem(
                  targetId,
                  getTestClasses(module, evaluator).toList.asJava
                ))
              case _: JavaModule =>
                None // TODO: maybe send a notification that this target has no test classes
            }

            items ++ newItem
        }

      new ScalaTestClassesResult(items.asJava)
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

  /**
   * External dependencies per module (e.g. ivy deps)
   */
  override def buildTargetDependencyModules(params: DependencyModulesParams)
      : CompletableFuture[DependencyModulesResult] =
    completable("buildTargetDependencyModules") {
      // TODO: implement
      val items = List.empty[DependencyModulesItem]
      new DependencyModulesResult(items.asJava)
    }

  /** Convert to BSP API. */
  implicit class BspModuleSupport(val m: BspModule) {

    def buildTargetId: BuildTargetIdentifier = bspIdByModule(m)

    def buildTarget: BuildTarget = {
      val s = m.bspBuildTarget
      val deps = m match {
        case jm: JavaModule =>
          jm.moduleDeps.collect {
            case bm: BspModule => bm.buildTargetId
          }
        case _ => Seq()
      }
      new BuildTarget(
        buildTargetId,
        s.tags.asJava,
        s.languageIds.asJava,
        deps.asJava,
        new BuildTargetCapabilities(s.canCompile, s.canTest, s.canRun, s.canDebug)
      ).tap { t =>
        s.displayName.foreach(t.setDisplayName)
        s.baseDirectory.foreach(p => t.setBaseDirectory(p.toNIO.toUri.toString))
        s.data._1.foreach(t.setDataKind)
        s.data._2.foreach(t.setData)
      }
    }

  }

}
