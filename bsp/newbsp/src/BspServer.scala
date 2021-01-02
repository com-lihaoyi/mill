package mill.bsp.newbsp

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream, PrintStream, PrintWriter}
import java.util.concurrent.{CompletableFuture, Executors}

import scala.concurrent.CancellationException
import scala.jdk.CollectionConverters.MapHasAsScala

import ch.epfl.scala.bsp4j.{BuildClient, BuildServer, CleanCacheParams, CleanCacheResult, CompileParams, CompileResult, DependencySourcesParams, DependencySourcesResult, InitializeBuildParams, InitializeBuildResult, InverseSourcesParams, InverseSourcesResult, ResourcesParams, ResourcesResult, RunParams, RunResult, SourcesParams, SourcesResult, TestParams, TestResult, WorkspaceBuildTargetsResult}
import mainargs.Flag
import mill.{MillBspRunner, MillMain, T}
import mill.main.MainRunner
import org.eclipse.lsp4j.jsonrpc.Launcher
import os.Path

class BspServer(runner: MainRunner, script: os.Path, debug: Option[OutputStream] = None) extends BuildServer { server =>

  object log {
    val debugOut = server.debug.map {
      case s: PrintStream => s
      case s => new PrintStream(s)
    }
    def debug(msg: String): Unit = debugOut.foreach{_.println(msg)}
  }

  override def buildInitialize(
      params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {
    log.debug(s"buildInitialize ${params}")
    ???
  }

  override def onBuildInitialized(): Unit = {
    log.debug(s"onBuildInitialized")
    ???
  }

  override def buildShutdown(): CompletableFuture[AnyRef] = ???

  override def onBuildExit(): Unit = ???

  override def workspaceBuildTargets()
    : CompletableFuture[WorkspaceBuildTargetsResult] = ???

  override def workspaceReload(): CompletableFuture[AnyRef] = {
    log.debug(s"workspaceReload")
    ???
  }

  override def buildTargetSources(
      params: SourcesParams): CompletableFuture[SourcesResult] = ???

  override def buildTargetInverseSources(
      params: InverseSourcesParams): CompletableFuture[InverseSourcesResult] =
    ???

  override def buildTargetDependencySources(params: DependencySourcesParams)
    : CompletableFuture[DependencySourcesResult] = ???

  override def buildTargetResources(
      params: ResourcesParams): CompletableFuture[ResourcesResult] = ???

  override def buildTargetCompile(
      params: CompileParams): CompletableFuture[CompileResult] = ???

  override def buildTargetTest(
      params: TestParams): CompletableFuture[TestResult] = ???

  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] =
    ???

  override def buildTargetCleanCache(
      params: CleanCacheParams): CompletableFuture[CleanCacheResult] = ???
}

case class BspServerConfig(
                            ammoniteCore: ammonite.main.Config.Core,
                          )

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
      predef = ammonite.main.Config.Predef(predefCode = "",
        noHomePredef = Flag()
      ),
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

    val bspServer = new BspServer(runner, os.pwd / "build.sc", Some(System.err))

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
            false
          case e: Exception =>
            System.err.println(
              s"""An exception occurred while connecting to the client.
                 |Cause: ${e.getCause}
                 |Message: ${e.getMessage}
                 |Exception class: ${e.getClass}
                 |Stack Trace: ${e.getStackTrace}""".stripMargin
            )
            false
        } finally {
          System.err.println("Shutting down executor")
          // executor.shutdown()
        }
//    millMain.main(args)
  }
}
