package mill.bsp.newbsp

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  OutputStream,
  PrintStream,
  PrintWriter
}
import java.util.concurrent.{CancellationException, CompletableFuture}

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import ch.epfl.scala.bsp4j._
import mainargs.Flag
import mill.api.Logger
import mill.bsp.ModuleUtils
import mill.eval.Evaluator
import mill.main.{MainRunner, RunScript}
import mill.util.{DummyLogger, PrintLogger}
import org.eclipse.lsp4j.jsonrpc.Launcher

class BspServer(
    runner: MainRunner,
    scriptPath: os.Path,
    debug: Option[OutputStream] = None,
    stopMill: () => Unit,
    stopBspServer: () => Unit
) extends BuildServer { server =>

  val wd: os.Path = scriptPath / os.up

  object log {
    val debugOut = server.debug.map {
      case s: PrintStream => s
      case s              => new PrintStream(s)
    }
    def debug(msg: String): Unit = debugOut.foreach { _.println(msg) }
  }

  // Helper
  protected def completable[T] = new CompletableFuture[T]()
  protected def completed[T](result: T) = {
    val future = new CompletableFuture[T]()
    future.complete(result)
    future
  }
  private[this] def completable[V](f: => V): CompletableFuture[V] = {
    val future = new CompletableFuture[V]()
    try {
      future.complete(f)
    } catch {
      case NonFatal(e) => future.completeExceptionally(e)
    }
    future
  }

  val bspVersion = "2.0.0"

  def withEvaluator[T](f: Evaluator => T): T = {
    val main = runner.initMain(false)

    val logger: Logger = debug match {
      case None => DummyLogger
      case Some(debugStream) =>
        val ps = new PrintStream(debugStream)
        PrintLogger(
          colored = true,
          disableTicker = false,
          colors = ammonite.util.Colors.Default,
          outStream = ps,
          infoStream = ps,
          errStream = ps,
          inStream = new ByteArrayInputStream(Array()),
          debugEnabled = true,
          context = ""
        )
    }

    RunScript.withEvaluator(
      home = wd / "out" / "ammonite",
      wd = wd / "out",
      path = scriptPath,
      instantiateInterpreter = main.instantiateInterpreter(),
      stateCache = None,
      log = logger,
      env = sys.env,
      keepGoing = false,
      systemProperties = sys.props.toMap,
      threadCount = Option(1)
    )(f(_))

//        result match{
//          case Res.Success(data) =>
//            val (eval, evalWatches, res) = data
//
//            stateCache = Some(Evaluator.State(eval.rootModule, eval.classLoaderSig, eval.workerCache, interpWatched))
//            val watched = () => {
//              val alreadyStale = evalWatches.exists(p => p.sig != PathRef(p.path, p.quick).sig)
//              // If the file changed between the creation of the original
//              // `PathRef` and the current moment, use random junk .sig values
//              // to force an immediate re-run. Otherwise calculate the
//              // pathSignatures the same way Ammonite would and hand over the
//              // values, so Ammonite can watch them and only re-run if they
//              // subsequently change
//              if (alreadyStale) evalWatches.map(p =>
//                (ammonite.interp.Watchable.Path(p.path), util.Random.nextLong())
//              )
//              else evalWatches.map(p =>
//                (ammonite.interp.Watchable.Path(p.path), ammonite.interp.Watchable.pathSignature(p.path))
//              )
//            }
//            (Res(res), () => interpWatched ++ watched())
//          case _ => (result, () => interpWatched)
//        }
//      }
//    )
//
//    )
//        RunScript.withEvaluator(
//          home = wd / "out" / "ammonite",
//          wd = wd,
//          path = script,
//          instantiateInterpreter: => Either[
//    (Res.Failing, Seq[(ammonite.interp.Watchable, Long)]),
//    ammonite.interp.Interpreter
//    ],
//    stateCache: Option[Evaluator.State],
//    log: PrintLogger,
//    env: Map[String, String],
//    keepGoing: Boolean,
//    systemProperties: Map[String, String],
//    threadCount: Option[Int]
//
//        )
////        new Evaluator(
//          home = ammoniteHome,
//          outPath = wd / "out",
//          externalOutPath = wd / "out",
//          rootModule = s.rootModule,
//          baseLogger = log,
//          classLoaderSig = s.classLoaderSig,
//          workerCache = s.workerCache,
//          env = env,
//          failFast = true,
//          threadCount = Some(1)
//        )
  }

  // TODO: Scan current build for supported languages (introduce new module to support extensions)
  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] =
    completable {
      val mutableCapabilities = new BuildServerCapabilities()
      mutableCapabilities.setCompileProvider(
        new CompileProvider(List("java", "scala").asJava)
      )
      mutableCapabilities.setRunProvider(
        new RunProvider(List("java", "scala").asJava)
      )
      mutableCapabilities.setTestProvider(
        new TestProvider(List("java", "scala").asJava)
      )
      mutableCapabilities.setDependencySourcesProvider(true)
      mutableCapabilities.setInverseSourcesProvider(true)
      mutableCapabilities.setResourcesProvider(true)

      //TODO: for now it's false, but will try to support this later
      mutableCapabilities.setBuildTargetChangedProvider(false)

      mutableCapabilities.setCanReload(true)

      log.debug(s"buildInitialize ${params}")

      new InitializeBuildResult(
        "mill-new-bsp",
        mill.BuildInfo.millVersion,
        bspVersion,
        mutableCapabilities
      )
    }

  override def onBuildInitialized(): Unit = {
    log.debug(s"onBuildInitialized")
    // Nice to know
  }

  override def buildShutdown(): CompletableFuture[AnyRef] = completable {
    log.debug("buildShutdown")
    stopMill()
    null.asInstanceOf[AnyRef]
  }

  override def onBuildExit(): Unit = completable {
    log.debug("onBuildExit")
    stopBspServer()
  }

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] = completable {
    log.debug("workspaceBuildTargets")
    withEvaluator { eval =>
      val modules = ModuleUtils.getModules(eval)
      val targets = ModuleUtils.getTargets(modules, eval)
      new WorkspaceBuildTargetsResult(targets.asJava)
    }
  }

  override def workspaceReload(): CompletableFuture[AnyRef] = {
    log.debug(s"workspaceReload")
    // we automatically reload if we detect changes
    completed(null)
  }

  override def buildTargetSources(
      params: SourcesParams
  ): CompletableFuture[SourcesResult] =
    ???

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] =
    ???

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = ???

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] = ???

  override def buildTargetCompile(
      params: CompileParams
  ): CompletableFuture[CompileResult] = ???

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] = ???

  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] =
    ???

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = ???
}

object BspServer {
  def main(args: Array[String]): Unit = {
//    val millMain = MillMain
//    millMain.registerBspRunner {
//      (runner: MainRunner,
//       buildScript: Path,
//       in: InputStream,
//       out: OutputStream,
//       err: OutputStream) =>
//        val server = new BspServer(runner, buildScript, Some(err))
    // val executor = Executors.newCachedThreadPool()

    val parser = mainargs.ParserForClass[BspServerConfig]
    val config = parser.constructOrThrow(args)

    val ammConfig = ammonite.main.Config(
      core = config.ammoniteCore,
      predef =
        ammonite.main.Config.Predef(predefCode = "", noHomePredef = Flag()),
      repl = ammonite.main.Config.Repl(
        banner = "",
        noRemoteLogging = Flag(),
        classBased = Flag()
      )
    )

    val stdout = new ByteArrayOutputStream()
    val stderr = new ByteArrayOutputStream()
    val stdin = new ByteArrayInputStream(Array())

    val runner = new mill.main.MainRunner(
      config = ammConfig,
      mainInteractive = false,
      disableTicker = true,
      outprintStream = new PrintStream(stdout),
      errPrintStream = new PrintStream(stderr),
      stdIn = stdin,
      stateCache0 = None,
      env = sys.env,
      setIdle = b => (),
      debugLog = true,
      keepGoing = true,
      systemProperties = sys.props.toMap,
      threadCount = Some(1),
      ringBell = false,
      wd = os.pwd
    )

    val bspServer = new BspServer(
      runner = runner,
      scriptPath = os.pwd / "build.sc",
      debug = Some(System.err),
      stopMill = { () =>
        System.err.println("TODO: Mill shutdown")
        ()
      },
      stopBspServer = { () =>
        System.err.println("TODO: BSP Server shutdown")
        ()
      }
    )

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(System.out)
        .setInput(System.in)
        .setLocalService(bspServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter((os.pwd / ".bsp" / "mill.log").toIO))
        // .setExecutorService(executor)
        .create()
      // millServer.onConnectWithClient(launcher.getRemoteProxy)
      launcher.startListening().get()
    } catch {
      case _: CancellationException =>
        System.err.println("The mill server was shut down.")
      case e: Exception =>
        System.err.println(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace}""".stripMargin
        )
    } finally {
      System.err.println("Shutting down executor")
      // executor.shutdown()
    }
//    millMain.main(args)
  }
}
