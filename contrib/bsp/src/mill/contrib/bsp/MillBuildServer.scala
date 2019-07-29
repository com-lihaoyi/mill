package mill.contrib.bsp

import sbt.testing._
import java.util.concurrent.CompletableFuture
import mill.scalalib.Lib.discoverTests
import ch.epfl.scala.bsp4j._
import com.google.gson.JsonObject
import mill.api.Result.{Skipped, Success}
import mill.{scalalib, _}
import mill.api.{BspContext, Result, Strict}
import mill.contrib.bsp.ModuleUtils._
import mill.eval.Evaluator
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import sbt.internal.inc._
import scala.collection.JavaConverters._
import mill.modules.Jvm
import mill.util.{Ctx, DummyLogger}
import mill.define.{Discover, ExternalModule}
import mill.main.EvaluatorScopt
import os.Path
import sbt.internal.util.{ConsoleOut, MainAppender, ManagedLogger}
import sbt.util.LogExchange
import scala.io.Source


class MillBuildServer(evaluator: Evaluator,
                      _bspVersion: String,
                      serverVersion:String,
                      languages: List[String]) extends ExternalModule with BuildServer with ScalaBuildServer  {

  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]
  val bspVersion: String = _bspVersion
  val supportedLanguages: List[String] = languages
  val millServerVersion: String = serverVersion
  var cancelator: () => Unit = () => ()
  val millEvaluator: Evaluator = evaluator
  var rootModule: JavaModule = ModuleUtils.getRootJavaModule(evaluator.rootModule)
  var millModules: Seq[JavaModule] = getMillModules(millEvaluator)
  var client: BuildClient = _
  var moduleToTargetId: Predef.Map[JavaModule, BuildTargetIdentifier] = ModuleUtils.getModuleTargetIdMap(
                                                                  millModules,
                                                                  evaluator
  )
  var targetIdToModule: Predef.Map[BuildTargetIdentifier, JavaModule] = targetToModule(moduleToTargetId)
  var moduleToTarget: Predef.Map[JavaModule, BuildTarget] =
                                  ModuleUtils.millModulesToBspTargets(millModules, rootModule, evaluator, List("scala", "java"))
  var moduleCodeToTargetId: Predef.Map[Int, BuildTargetIdentifier] =
    for ( (targetId, module) <- targetIdToModule ) yield (module.hashCode(), targetId)

  var initialized = false
  var clientInitialized = false

  val ctx: Ctx.Log with Ctx.Home = new Ctx.Log with Ctx.Home {
    val log: DummyLogger.type = mill.util.DummyLogger
    val home: Path = os.pwd
  }

  override def onConnectWithClient(server: BuildClient): Unit =
    client = server

  override def buildInitialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {

    val capabilities = new BuildServerCapabilities
    capabilities.setCompileProvider(new CompileProvider(List("java", "scala").asJava))
    capabilities.setRunProvider(new RunProvider(List("java", "scala").asJava))
    capabilities.setTestProvider(new TestProvider(List("java", "scala").asJava))
    capabilities.setDependencySourcesProvider(true)
    capabilities.setInverseSourcesProvider(true)
    capabilities.setResourcesProvider(true)
    capabilities.setBuildTargetChangedProvider(false) //TODO: for now it's false, but will try to support this later
    val future = new CompletableFuture[InitializeBuildResult]()
    future.complete(new InitializeBuildResult("mill-bsp", millServerVersion, bspVersion, capabilities))
    initialized = true
    future
  }

  override def onBuildInitialized(): Unit = {
    clientInitialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    handleExceptions[String, Object](_ => "shut down this server".asInstanceOf[Object], "")
  }

  override def onBuildExit(): Unit = {
    cancelator()
  }

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] = {
      recomputeTargets()
      handleExceptions[String, WorkspaceBuildTargetsResult](
        _ => new WorkspaceBuildTargetsResult(moduleToTarget.values.toList.asJava),
        "")
  }

  override def buildTargetSources(sourcesParams: SourcesParams): CompletableFuture[SourcesResult] = {
    recomputeTargets()
    def computeSourcesResult: SourcesResult = {
      var items = List[SourcesItem]()

      for (targetId <- sourcesParams.getTargets.asScala) {
        var itemSources = List[SourceItem]()

        val sources = evaluateInformativeTask(evaluator, targetIdToModule(targetId).sources, Agg.empty[PathRef]).
                      map(pathRef => pathRef.path).toSeq
        val generatedSources = evaluateInformativeTask(evaluator,
                                                        targetIdToModule(targetId).generatedSources,
                                                        Agg.empty[PathRef]).
                                                        map(pathRef => pathRef.path).toSeq

        for (source <- sources) {
          itemSources ++= List(
            new SourceItem(source.toIO.toURI.toString, SourceItemKind.DIRECTORY, false))
        }

        for (genSource <- generatedSources) {
          itemSources ++= List(
            new SourceItem(genSource.toIO.toURI.toString, SourceItemKind.DIRECTORY, true))
        }

        items ++= List(new SourcesItem(targetId, itemSources.asJava))
      }

      new SourcesResult(items.asJava)
    }
    handleExceptions[String, SourcesResult](_ => computeSourcesResult, "")
  }

  override def buildTargetInverseSources(inverseSourcesParams: InverseSourcesParams):
                                                        CompletableFuture[InverseSourcesResult] = {
    recomputeTargets()

    def getInverseSourcesResult: InverseSourcesResult = {
      val textDocument = inverseSourcesParams.getTextDocument
      val targets = millModules.filter(m => ModuleUtils.evaluateInformativeTask(
                    millEvaluator, m.allSourceFiles, Seq.empty[PathRef]).
                    map(pathRef => pathRef.path.toIO.toURI.toString).
                    contains(textDocument.getUri)).
                    map(m => moduleToTargetId(m))
      new InverseSourcesResult(targets.asJava)
    }
    handleExceptions[String, InverseSourcesResult](_ => getInverseSourcesResult, "")
  }

  override def buildTargetDependencySources(dependencySourcesParams: DependencySourcesParams):
                                                              CompletableFuture[DependencySourcesResult] = {
    recomputeTargets()
    def getDependencySources: DependencySourcesResult = {
      var items = List[DependencySourcesItem]()

      for (targetId <- dependencySourcesParams.getTargets.asScala) {
        val millModule = targetIdToModule(targetId)
        var sources = evaluateInformativeTask(evaluator,
                                              millModule.resolveDeps(millModule.transitiveIvyDeps),
                                              Agg.empty[PathRef]) ++
                      evaluateInformativeTask(evaluator,
                                              millModule.resolveDeps(millModule.compileIvyDeps),
                                              Agg.empty[PathRef]) ++
                      evaluateInformativeTask(evaluator,
                                              millModule.unmanagedClasspath,
                                              Agg.empty[PathRef])
        millModule match {
          case _: ScalaModule => sources ++= evaluateInformativeTask(evaluator,
                                    millModule.resolveDeps(millModule.asInstanceOf[ScalaModule].scalaLibraryIvyDeps),
                                    Agg.empty[PathRef])
          case _: JavaModule => sources ++= List()
        }
        items ++= List(new DependencySourcesItem(targetId, sources.
                                                    map(pathRef => pathRef.path.toIO.toURI.toString).
                                                    toList.asJava))
      }

      new DependencySourcesResult(items.asJava)
    }
    handleExceptions[String, DependencySourcesResult](_ => getDependencySources, "")
  }

  override def buildTargetResources(resourcesParams: ResourcesParams): CompletableFuture[ResourcesResult] = {
    recomputeTargets()
    def getResources: ResourcesResult = {
      var items = List[ResourcesItem]()

      for (targetId <- resourcesParams.getTargets.asScala) {
        val millModule = targetIdToModule(targetId)
        val resources = evaluateInformativeTask(evaluator, millModule.resources, Agg.empty[PathRef]).
                        flatMap(pathRef => os.walk(pathRef.path)).
                        map(path => path.toIO.toURI.toString).
                        toList.asJava
        items ++= List(new ResourcesItem(targetId, resources))
      }

      new ResourcesResult(items.asJava)
    }
    handleExceptions[String, ResourcesResult](_ => getResources, "")
  }

  // construct the ManagedLogger that will go into the compilation problems reporter
  private[this] def getCompilationLogger: ManagedLogger = {
      val consoleAppender = MainAppender.defaultScreen(ConsoleOut.printStreamOut(
        millEvaluator.log.outputStream
      ))
      val l = LogExchange.logger("Hello")
      LogExchange.unbindLoggerAppenders("Hello")
      LogExchange.bindLoggerAppenders("Hello", (consoleAppender -> sbt.util.Level.Info) :: Nil)
      l
  }

  // define the function that spawns compilation reporter for each module based on the
  // module's hash code TODO: find something more reliable than the hash code
  private[this] def getBspLoggedReporterPool(params: Parameters, taskStartMessage: String => String,
                                             taskStartDataKind: String, taskStartData: BuildTargetIdentifier => Object):
                                                                Int => Option[ManagedLoggedReporter] = {
    int: Int =>
      if (moduleCodeToTargetId.contains(int)) {
        val targetId = moduleCodeToTargetId(int)
        val taskId = new TaskId(targetIdToModule(targetId).compile.hashCode.toString)
        val taskStartParams = new TaskStartParams(taskId)
        taskStartParams.setEventTime(System.currentTimeMillis())
        taskStartParams.setData(taskStartData(targetId))
        taskStartParams.setDataKind(taskStartDataKind)
        taskStartParams.setMessage(taskStartMessage(moduleToTarget(targetIdToModule(targetId)).getDisplayName))
        client.onBuildTaskStart(taskStartParams)
        Option(new BspLoggedReporter(client,
          targetId,
          taskId,
          params.getOriginId,
          10, getCompilationLogger))}
      else Option.empty[ManagedLoggedReporter]
  }

  //TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(compileParams: CompileParams): CompletableFuture[CompileResult] = {
    recomputeTargets()
    def getCompileResult: CompileResult = {
      val params = TaskParameters.fromCompileParams(compileParams)
      val taskId = params.hashCode()
      val compileTasks = Strict.Agg(params.getTargets.
                          filter(targetId => targetId != moduleToTarget(rootModule).getId).
                          map(targetId => targetIdToModule(targetId).compile):_*)
      val result = millEvaluator.evaluate(compileTasks,
                    getBspLoggedReporterPool(params, t => s"Started compiling target: $t",
                    "compile-task", (targetId: BuildTargetIdentifier) => new CompileTask(targetId)),
                    new BspContext {
                      override def args: Seq[String] = params.getArguments.getOrElse(Seq.empty[String])
                      override def logStart(event: Event): Unit = {}

                      override def logFinish(event: Event): Unit = {}
                    },
                    new MillBspLogger(client, taskId, millEvaluator.log)
      )
      val compileResult = new CompileResult(getStatusCode(result))
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult //TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }
    handleExceptions[String, CompileResult](_ => getCompileResult, "")
  }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] = {
    recomputeTargets()
    def getRunResult: RunResult = {
      val params = TaskParameters.fromRunParams(runParams)
      val module = targetIdToModule(params.getTargets.head)
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args:_*)
      val runResult = millEvaluator.evaluate(Strict.Agg(runTask),
        getBspLoggedReporterPool(
          params,
          t => s"Started compiling target: $t",
          "compile-task",
          (targetId: BuildTargetIdentifier) => new CompileTask(targetId)),
        logger = new MillBspLogger(client, runTask.hashCode(), millEvaluator.log))
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
    handleExceptions[String, RunResult](_ => getRunResult, "")
  }

  private[this] def getStatusCodePerTask(results: Evaluator.Results, task: mill.define.Task[_]): StatusCode = {
    results.results(task) match {
      case _: Success[_] => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

  // Get the execution status code given the results from Evaluator.evaluate
  private[this] def getStatusCode(results: Evaluator.Results): StatusCode = {

    val statusCodes = results.results.keys.map(task => getStatusCodePerTask(results, task)).toSeq
    if (statusCodes.contains(StatusCode.ERROR))
      StatusCode.ERROR
    else if (statusCodes.contains(StatusCode.CANCELLED))
      StatusCode.CANCELLED
    else
      StatusCode.OK
  }

  override def buildTargetTest(testParams: TestParams): CompletableFuture[TestResult] = {
    recomputeTargets()
    def getTestResult: TestResult = {
      val params = TaskParameters.fromTestParams(testParams)
      val argsMap = try {
                      val scalaTestParams = testParams.getData.asInstanceOf[JsonObject]
                      (for (testItem <- scalaTestParams.get("testClasses").getAsJsonArray.asScala)
                        yield (
                          testItem.getAsJsonObject.get("target").getAsJsonObject.get("uri").getAsString,
                          testItem.getAsJsonObject.get("classes").getAsJsonArray
                            .asScala.map(elem => elem.getAsString).toSeq)).toMap
                    } catch {
                      case _: Exception => (for (targetId <- testParams.getTargets.asScala) yield
                        (targetId.getUri, Seq.empty[String])).toMap

                    }

      var overallStatusCode = StatusCode.OK
      for (targetId <- testParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        module match {
          case m: TestModule => val testModule = m.asInstanceOf[TestModule]
            val testTask = testModule.testLocal(argsMap(targetId.getUri):_*)

            // notifying the client that the testing of this build target started
            val taskStartParams = new TaskStartParams(new TaskId(testTask.hashCode().toString))
            taskStartParams.setEventTime(System.currentTimeMillis())
            taskStartParams.setMessage("Testing target: " + targetId)
            taskStartParams.setDataKind("test-task")
            taskStartParams.setData(new TestTask(targetId))
            client.onBuildTaskStart(taskStartParams)

            val bspContext = new BspTestReporter(
              client, targetId,
              new TaskId(testTask.hashCode().toString),
              Seq.empty[String])

            val results = millEvaluator.evaluate(
              Strict.Agg(testTask),
              getBspLoggedReporterPool(params, t => s"Started compiling target: $t",
                "compile-task", (targetId: BuildTargetIdentifier) => new CompileTask(targetId)),
              bspContext,
              new MillBspLogger(client, testTask.hashCode, millEvaluator.log))
            val endTime = System.currentTimeMillis()
            val statusCode = getStatusCode(results)
            statusCode match {
              case StatusCode.ERROR => overallStatusCode = StatusCode.ERROR
              case StatusCode.CANCELLED => overallStatusCode =
                if (overallStatusCode == StatusCode.ERROR) StatusCode.ERROR else StatusCode.CANCELLED
              case StatusCode.OK =>
            }
            // notifying the client that the testing of this build target ended
            val taskFinishParams = new TaskFinishParams(
              new TaskId(testTask.hashCode().toString),
              statusCode
            )
            taskFinishParams.setEventTime(endTime)
            taskFinishParams.setMessage("Finished testing target: " +
              moduleToTarget(targetIdToModule(targetId)).getDisplayName)
            taskFinishParams.setDataKind("test-report")
            taskFinishParams.setData(bspContext.getTestReport)
            client.onBuildTaskFinish(taskFinishParams)

          case _ =>
        }
      }
      val testResult = new TestResult(overallStatusCode)
      params.getOriginId match {
        case None => testResult
        case Some(id) =>
           //TODO: Add the messages from mill to the data field?
          testResult.setOriginId(id)
          testResult
      }
    }
    handleExceptions[String, TestResult](_ => getTestResult, "")
  }

  override def buildTargetCleanCache(cleanCacheParams: CleanCacheParams): CompletableFuture[CleanCacheResult] = {
      recomputeTargets()
      def getCleanCacheResult: CleanCacheResult = {
        var msg = ""
        var cleaned = true
        for (targetId <- cleanCacheParams.getTargets.asScala) {
          val module = targetIdToModule(targetId)
          val cleanCommand = Array("java",
            s"-DMILL_CLASSPATH=${System.getProperty("MILL_CLASSPATH")}",
            s"-DMILL_VERSION=${System.getProperty("MILL_VERSION")}",
            "-Djna.nosys=true", "-cp",
            System.getProperty("MILL_CLASSPATH"),
            "mill.MillMain", "clean",
            s"${module.millModuleSegments.render}.compile")
          val process = Runtime.getRuntime.exec(cleanCommand, null, os.pwd.toIO)

          val processIn = process.getInputStream
          val processErr = process.getErrorStream

          val errMessage = Source.fromInputStream(processErr).getLines().mkString("\n")
          val message = Source.fromInputStream(processIn).getLines().mkString("\n")
          msg += s"Cleaning cache for target $targetId produced the following message: $message, $errMessage"
          if (msg.contains("failed") || msg.contains("Error")) {
            cleaned = false
          }
          process.waitFor()
        }
        new CleanCacheResult(msg, cleaned)
      }
      handleExceptions[String, CleanCacheResult](_ => getCleanCacheResult, "")
    }

  override def buildTargetScalacOptions(scalacOptionsParams: ScalacOptionsParams):
                                                  CompletableFuture[ScalacOptionsResult] = {
    recomputeTargets()
    def getScalacOptionsResult: ScalacOptionsResult = {
      var targetScalacOptions = List.empty[ScalacOptionsItem]
      for (targetId <- scalacOptionsParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        module match {
          case m: ScalaModule =>
            val options = evaluateInformativeTask(evaluator, m.scalacOptions, Seq.empty[String]).toList
            val classpath = evaluateInformativeTask(evaluator, m.runClasspath, Agg.empty[PathRef]).
              map(pathRef => pathRef.path.toIO.toURI.toString).toList
            val classDirectory = (Evaluator.resolveDestPaths(os.pwd / "out" , m.millModuleSegments).
                                    dest / "classes").toIO.toURI.toString

            targetScalacOptions ++= List(new ScalacOptionsItem(targetId, options.asJava, classpath.asJava, classDirectory))
          case _: JavaModule => targetScalacOptions ++= List()
        }
      }
      new ScalacOptionsResult(targetScalacOptions.asJava)
    }
    handleExceptions[String, ScalacOptionsResult](_ => getScalacOptionsResult, "")
  }

  //TODO: In the case when mill fails to provide a main classes because multiple were
  // defined for the same module, do something so that those can still be detected
  // such that IntelliJ can run any of them
  override def buildTargetScalaMainClasses(scalaMainClassesParams: ScalaMainClassesParams):
                                                  CompletableFuture[ScalaMainClassesResult] = {
    recomputeTargets()
    def getScalaMainClasses: ScalaMainClassesResult = {
      var items = List.empty[ScalaMainClassesItem]
      for (targetId <- scalaMainClassesParams.getTargets.asScala) {
        val module = targetIdToModule(targetId)
        val scalaMainClasses = getTaskResult(millEvaluator, module.finalMainClassOpt) match {
          case result: Result.Success[Any] => result.asSuccess.get.value match {
            case mainClass: Right[String, String] =>
              List(new ScalaMainClass(
                                mainClass.value,
                                List.empty[String].asJava,
                                evaluateInformativeTask(evaluator, module.forkArgs, Seq.empty[String]).
                                  toList.asJava))
            case msg: Left[String, String] =>
              val messageParams = new ShowMessageParams(MessageType.WARNING, msg.value)
              messageParams.setOriginId(scalaMainClassesParams.getOriginId)
              client.onBuildShowMessage(messageParams) // tell the client that no main class was found or specified
              List.empty[ScalaMainClass]
          }
          case _ => List.empty[ScalaMainClass]
        }
        val item = new ScalaMainClassesItem (targetId , scalaMainClasses.asJava)
        items ++= List(item)
        }
      new ScalaMainClassesResult(items.asJava)
    }
    handleExceptions[String, ScalaMainClassesResult](_ => getScalaMainClasses, "")
  }

  // Detect and return the test classes contained in the given TestModule
  private[this] def getTestClasses(module: TestModule) (implicit ctx: Ctx.Home): Seq[String] = {
    val runClasspath = getTaskResult(millEvaluator, module.runClasspath)
    val frameworks = getTaskResult(millEvaluator, module.testFrameworks)
    val compilationResult = getTaskResult(millEvaluator, module.compile)

    (runClasspath, frameworks, compilationResult) match {
      case (Result.Success(classpath), Result.Success(testFrameworks), Result.Success(compResult)) =>
        val classFingerprint = Jvm.inprocess(classpath.asInstanceOf[Seq[PathRef]].map(_.path),
          classLoaderOverrideSbtTesting = true,
          isolated = true,
          closeContextClassLoaderWhenDone = false, cl => {
            val fs = TestRunner.frameworks(testFrameworks.asInstanceOf[Seq[String]])(cl)
            fs.flatMap(framework =>
              discoverTests(cl, framework, Agg(compResult.asInstanceOf[CompilationResult].
                classes.path)))
          })
        classFingerprint.map(classF => classF._1.getName.stripSuffix("$"))
      case _ => Seq.empty[String] //TODO: or send notification that something went wrong
    }
  }

  override def buildTargetScalaTestClasses(scalaTestClassesParams: ScalaTestClassesParams):
                                                  CompletableFuture[ScalaTestClassesResult] = {
    recomputeTargets()
    def getScalaTestClasses (implicit ctx: Ctx.Home): ScalaTestClassesResult = {
      var items = List.empty[ScalaTestClassesItem]
      for (targetId <- scalaTestClassesParams.getTargets.asScala) {
        targetIdToModule(targetId) match {
          case module: TestModule =>
                    items ++= List(new ScalaTestClassesItem(targetId, getTestClasses(module).toList.asJava))
          case _: JavaModule => //TODO: maybe send a notification that this target has no test classes
        }
      }
      new ScalaTestClassesResult(items.asJava)
    }
    handleExceptions[Ctx.Home, ScalaTestClassesResult](c => getScalaTestClasses(c), ctx)
  }

  // Given the mapping from modules to targetIds, construct the mapping from targetIds to modules
  private[this] def targetToModule(moduleToTargetId: Predef.Map[JavaModule, BuildTargetIdentifier]):
                                                      Predef.Map[BuildTargetIdentifier, JavaModule] = {
      moduleToTargetId.keys.map(mod => (moduleToTargetId(mod), mod)).toMap

  }

  // Resolve all the mill modules contained in the project
  private[this] def getMillModules(ev: Evaluator): Seq[JavaModule] = {
    ev.rootModule.millInternal.segmentsToModules.values.
      collect {
        case m: scalalib.JavaModule => m
      }.toSeq ++ Seq(rootModule)
  }

  // Recompute the modules in the project in case any changes to the build took place
  // and update all the mappings that depend on this info
  private[this] def recomputeTargets(): Unit = {
    rootModule = ModuleUtils.getRootJavaModule(millEvaluator.rootModule)
    millModules = getMillModules(millEvaluator)
    moduleToTargetId = ModuleUtils.getModuleTargetIdMap(millModules, millEvaluator)
    targetIdToModule = targetToModule(moduleToTargetId)
    moduleToTarget = ModuleUtils.millModulesToBspTargets(millModules, rootModule, evaluator, List("scala", "java"))
  }

  // Given a function that take input of type T and return output of type V,
  // apply the function on the given inputs and return a completable future of
  // the result. If the execution of the function raises an Exception, complete
  // the future exceptionally. Also complete exceptionally if the server was not
  // yet initialized.
  private[this] def handleExceptions[T, V](serverMethod: T => V, input: T): CompletableFuture[V] = {
    val future = new CompletableFuture[V]()
    if (initialized) {
      try {
        future.complete(serverMethod(input))
      } catch {
        case e: Exception => future.completeExceptionally(e)
      }
    } else {
      future.completeExceptionally(
        new Exception("Can not respond to any request before receiving the `initialize` request.")
      )
    }
    future
  }
}
