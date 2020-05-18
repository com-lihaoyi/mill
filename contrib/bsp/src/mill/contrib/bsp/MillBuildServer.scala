package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import mill._
import mill.api.{DummyTestReporter, Result, Strict}
import mill.contrib.bsp.ModuleUtils._
import mill.contrib.bsp.Utils._
import mill.define.Segment.Label
import mill.define.{Discover, ExternalModule}
import mill.eval.Evaluator
import mill.main.{EvaluatorScopt, MainModule}
import mill.scalalib._
import mill.util.{Ctx, DummyLogger}
import os.Path
import scala.collection.JavaConverters._

class MillBuildServer(evaluator: Evaluator, bspVersion: String, serverVersion: String)
    extends ExternalModule
    with BuildServer
    with ScalaBuildServer {
  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[MillBuildServer.this.type] = Discover[this.type]
  implicit val ctx: Ctx.Log with Ctx.Home = new Ctx.Log with Ctx.Home {
    val log: DummyLogger.type = mill.util.DummyLogger
    val home: Path = os.pwd
  }
  var cancelator: () => Unit = () => ()
  var client: BuildClient = _
  var initialized = false
  var clientInitialized = false

  override def onConnectWithClient(server: BuildClient): Unit = client = server

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
    future.complete(new InitializeBuildResult("mill-bsp", serverVersion, bspVersion, capabilities))
    initialized = true
    future
  }

  override def onBuildInitialized(): Unit = {
    clientInitialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] =
    handleExceptions {
      "shut down this server".asInstanceOf[Object]
    }

  override def onBuildExit(): Unit = cancelator()

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    handleExceptions {
      val targets = getTargets(getModules(evaluator), evaluator)

      // Remove the sortBy, reverse and distinctBy when https://youtrack.jetbrains.com/issue/SCL-17551 is resolved
      new WorkspaceBuildTargetsResult(targets.sortBy(_.getId.getUri).reverse.distinctBy(_.getBaseDirectory).asJava)
    }

  override def buildTargetSources(sourcesParams: SourcesParams): CompletableFuture[SourcesResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      def sourceItem(source: Path, generated: Boolean) = {
        val file = source.toIO
        new SourceItem(
          file.toURI.toString,
          if (file.isFile) SourceItemKind.FILE else SourceItemKind.DIRECTORY,
          generated
        )
      }

      val items = sourcesParams.getTargets.asScala.foldLeft(Seq.empty[SourcesItem]) { (items, targetId) =>
        val newItem =
          if (targetId == getMillBuildTargetId(evaluator))
            new SourcesItem(
              targetId,
              Seq(sourceItem(evaluator.rootModule.millSourcePath / "src", generated = false)).asJava // Intellij needs one
            )
          else {
            val module = getModule(targetId, modules)
            val sources = evaluateInformativeTask(evaluator, module.sources, Seq.empty[PathRef])
              .map(p => sourceItem(p.path, generated = false))
            val generatedSources = evaluateInformativeTask(evaluator, module.generatedSources, Seq.empty[PathRef])
              .map(p => sourceItem(p.path, generated = true))

            new SourcesItem(targetId, (sources ++ generatedSources).asJava)
          }

        items :+ newItem
      }

      new SourcesResult(items.asJava)
    }

  override def buildTargetInverseSources(
      inverseSourcesParams: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val targets = modules
        .filter(m =>
          ModuleUtils
            .evaluateInformativeTask(evaluator, m.allSourceFiles, Seq.empty[PathRef])
            .map(_.path.toIO.toURI.toString)
            .contains(inverseSourcesParams.getTextDocument.getUri)
        )
        .map(getTargetId)

      new InverseSourcesResult(targets.asJava)
    }

  override def buildTargetDependencySources(
      dependencySourcesParams: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val items =
        dependencySourcesParams.getTargets.asScala.foldLeft(Seq.empty[DependencySourcesItem]) { (items, targetId) =>
          val newItem =
            if (targetId == getMillBuildTargetId(evaluator)) new DependencySourcesItem(targetId, Seq.empty.asJava)
            else {
              val sources = evaluateInformativeTask(
                evaluator,
                getModule(targetId, modules).compileClasspath,
                Agg.empty[PathRef]
              )

              new DependencySourcesItem(targetId, sources.map(_.path.toIO.toURI.toString).iterator.to(Seq).asJava)
            }

          items :+ newItem
        }

      new DependencySourcesResult(items.asJava)
    }

  override def buildTargetResources(resourcesParams: ResourcesParams): CompletableFuture[ResourcesResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val items = resourcesParams.getTargets.asScala.foldLeft(Seq.empty[ResourcesItem]) { (items, targetId) =>
        val newItem =
          if (targetId == getMillBuildTargetId(evaluator)) new ResourcesItem(targetId, Seq.empty.asJava)
          else {
            val resources =
              evaluateInformativeTask(evaluator, getModule(targetId, modules).resources, Seq.empty[PathRef])
                .filter(pathRef => os.exists(pathRef.path))

            new ResourcesItem(targetId, resources.map(_.path.toNIO.toUri.toString).asJava)
          }

        items :+ newItem
      }

      new ResourcesResult(items.asJava)
    }

  // TODO: if the client wants to give compilation arguments and the module
  // already has some from the build file, what to do?
  override def buildTargetCompile(compileParams: CompileParams): CompletableFuture[CompileResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val params = TaskParameters.fromCompileParams(compileParams)
      val taskId = params.hashCode()
      val compileTasks = Strict.Agg(params.getTargets.map(targetId => getModule(targetId, modules).compile): _*)
      val result = evaluator.evaluate(
        compileTasks,
        getBspLoggedReporterPool(
          params,
          t => s"Started compiling target: $t",
          TaskDataKind.COMPILE_TASK,
          targetId => new CompileTask(targetId),
          modules,
          evaluator,
          client
        ),
        DummyTestReporter,
        new MillBspLogger(client, taskId, evaluator.baseLogger)
      )
      val compileResult = new CompileResult(getStatusCode(result))
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }

  override def buildTargetRun(runParams: RunParams): CompletableFuture[RunResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val params = TaskParameters.fromRunParams(runParams)
      val module = getModule(params.getTargets.head, modules)
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args: _*)
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        getBspLoggedReporterPool(
          params,
          t => s"Started compiling target: $t",
          TaskDataKind.COMPILE_TASK,
          targetId => new CompileTask(targetId),
          modules,
          evaluator,
          client
        ),
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
    handleExceptions {
      val modules = getModules(evaluator)
      val targets = getTargets(modules, evaluator)

      val params = TaskParameters.fromTestParams(testParams)
      val argsMap =
        try {
          val scalaTestParams = testParams.getData.asInstanceOf[JsonObject]
          (for (testItem <- scalaTestParams.get("testClasses").getAsJsonArray.asScala)
            yield (
              testItem.getAsJsonObject.get("target").getAsJsonObject.get("uri").getAsString,
              testItem.getAsJsonObject.get("classes").getAsJsonArray.asScala.map(elem => elem.getAsString).toSeq
            )).toMap
        } catch {
          case _: Exception =>
            (for (targetId <- testParams.getTargets.asScala) yield (targetId.getUri, Seq.empty[String])).toMap
        }

      var overallStatusCode = StatusCode.OK
      for (targetId <- testParams.getTargets.asScala) {
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
              new BspTestReporter(client, targetId, new TaskId(testTask.hashCode().toString), Seq.empty[String])

            val results = evaluator.evaluate(
              Strict.Agg(testTask),
              getBspLoggedReporterPool(
                params,
                t => s"Started compiling target: $t",
                TaskDataKind.COMPILE_TASK,
                (targetId: BuildTargetIdentifier) => new CompileTask(targetId),
                modules,
                evaluator,
                client
              ),
              testReporter,
              new MillBspLogger(client, testTask.hashCode, evaluator.baseLogger)
            )
            val endTime = System.currentTimeMillis()
            val statusCode = getStatusCode(results)
            statusCode match {
              case StatusCode.ERROR => overallStatusCode = StatusCode.ERROR
              case StatusCode.CANCELLED =>
                overallStatusCode =
                  if (overallStatusCode == StatusCode.ERROR) StatusCode.ERROR else StatusCode.CANCELLED
              case StatusCode.OK =>
            }
            // notifying the client that the testing of this build target ended
            val taskFinishParams = new TaskFinishParams(
              new TaskId(testTask.hashCode().toString),
              statusCode
            )
            taskFinishParams.setEventTime(endTime)
            taskFinishParams.setMessage(
              "Finished testing target" +
                targets.find(_.getId == targetId).fold("")(t => s": ${t.getDisplayName}")
            )
            taskFinishParams.setDataKind(TaskDataKind.TEST_REPORT)
            taskFinishParams.setData(testReporter.getTestReport)
            client.onBuildTaskFinish(taskFinishParams)

          case _ =>
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

  override def buildTargetCleanCache(cleanCacheParams: CleanCacheParams): CompletableFuture[CleanCacheResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      var msg = ""
      var cleaned = true
      for (targetId <- cleanCacheParams.getTargets.asScala) {
        val module = getModule(targetId, modules)
        val mainModule = new MainModule {
          override implicit def millDiscover: Discover[_] = {
            Discover[this.type]
          }
        }
        val cleanTask = mainModule.clean(evaluator, Seq(s"${module.millModuleSegments.render}.compile"): _*)
        val cleanResult = evaluator.evaluate(
          Strict.Agg(cleanTask),
          logger = new MillBspLogger(client, cleanTask.hashCode, evaluator.baseLogger)
        )
        if (cleanResult.failing.keyCount > 0) {
          cleaned = false
          msg += s" Target ${module.millModuleSegments.render} could not be cleaned. See message from mill: \n"
          cleanResult.results(cleanTask) match {
            case fail: Result.Failure[Any] => msg += fail.msg + "\n"
            case _ => msg += "could not retrieve message"
          }
        } else {
          msg += s"${module.millModuleSegments.render} cleaned \n"

          val outDir = Evaluator
            .resolveDestPaths(
              os.pwd / "out",
              module.millModuleSegments ++
                Seq(Label("compile"))
            )
            .out
          while (os.exists(outDir)) {
            Thread.sleep(10)
          }
        }
      }
      new CleanCacheResult(msg, cleaned)
    }

  override def buildTargetScalacOptions(
      scalacOptionsParams: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val items = scalacOptionsParams.getTargets.asScala.foldLeft(Seq.empty[ScalacOptionsItem]) { (items, targetId) =>
        val newItem =
          if (targetId == getMillBuildTargetId(evaluator)) None
          else {
            getModule(targetId, modules) match {
              case m: ScalaModule =>
                val options = evaluateInformativeTask(evaluator, m.scalacOptions, Seq.empty[String]).toList
                val classpath = evaluateInformativeTask(evaluator, m.runClasspath, Seq.empty[PathRef])
                  .map(_.path.toNIO.toUri.toString)
                  .toList
                val classDirectory = (Evaluator
                  .resolveDestPaths(
                    os.pwd / "out",
                    m.millModuleSegments ++ Seq(Label("compile"))
                  )
                  .dest / "classes").toNIO.toUri.toString

                Some(new ScalacOptionsItem(targetId, options.asJava, classpath.asJava, classDirectory))
              case _: JavaModule => None
            }
          }

        items ++ newItem
      }

      new ScalacOptionsResult(items.asJava)
    }

  // TODO: In the case when mill fails to provide a main classes because multiple were
  // defined for the same module, do something so that those can still be detected
  // such that IntelliJ can run any of them
  override def buildTargetScalaMainClasses(
      scalaMainClassesParams: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] =
    handleExceptions {
      val modules = getModules(evaluator)

      val items =
        scalaMainClassesParams.getTargets.asScala.foldLeft(Seq.empty[ScalaMainClassesItem]) { (items, targetId) =>
          val module = getModule(targetId, modules)
          val scalaMainClasses = getTaskResult(evaluator, module.finalMainClassOpt) match {
            case result: Result.Success[_] =>
              result.asSuccess.get.value match {
                case mainClass: Right[String, String] =>
                  Seq(
                    new ScalaMainClass(
                      mainClass.value,
                      Seq.empty[String].asJava,
                      evaluateInformativeTask(evaluator, module.forkArgs, Seq.empty[String]).toList.asJava
                    )
                  )
                case msg: Left[String, String] =>
                  val messageParams = new ShowMessageParams(MessageType.WARNING, msg.value)
                  messageParams.setOriginId(scalaMainClassesParams.getOriginId)
                  client.onBuildShowMessage(messageParams) // tell the client that no main class was found or specified
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
    handleExceptions {
      val modules = getModules(evaluator)

      val items =
        scalaTestClassesParams.getTargets.asScala.foldLeft(Seq.empty[ScalaTestClassesItem]) { (items, targetId) =>
          val newItem = getModule(targetId, modules) match {
            case module: TestModule =>
              Some(new ScalaTestClassesItem(targetId, getTestClasses(module, evaluator).toList.asJava))
            case _: JavaModule => None // TODO: maybe send a notification that this target has no test classes
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
  private[this] def handleExceptions[V](f: => V): CompletableFuture[V] = {
    val future = new CompletableFuture[V]()
    if (initialized) {
      try {
        future.complete(f)
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
