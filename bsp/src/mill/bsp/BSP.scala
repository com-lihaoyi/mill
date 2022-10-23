package mill.bsp

import ch.epfl.scala.bsp4j.BuildClient

import java.io.{InputStream, PrintStream, PrintWriter}
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executors
import mill.{BuildInfo, MillMain, T}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult}
import org.eclipse.lsp4j.jsonrpc.Launcher

import scala.concurrent.{Await, CancellationException, Promise}
import upickle.default.write

import scala.concurrent.duration.Duration
import scala.util.Using
import scala.util.chaining.scalaUtilChainingOps

object BSP extends ExternalModule {
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[this.type] = Discover[this.type]
  val bspProtocolVersion = "2.0.0"
  val languages = Seq("scala", "java")
  val serverName = "mill-bsp"

  private[this] var millServerHandle = Promise[BspServerHandle]()

  /**
   * Installs the mill-bsp server. It creates a json file
   * with connection details in the ./.bsp directory for
   * a potential client to find.
   *
   * If a .bsp folder with a connection file already
   * exists in the working directory, it will be
   * overwritten and a corresponding message will be displayed
   * in stdout.
   *
   * If the creation of the .bsp folder fails due to any other
   * reason, the message and stacktrace of the exception will be
   * printed to stdout.
   */
  def install(evaluator: Evaluator, jobs: Int = 1): Command[Unit] =
    T.command {
      val bspDirectory = evaluator.rootModule.millSourcePath / ".bsp"
      val bspFile = bspDirectory / s"${serverName}.json"
      if (os.exists(bspFile)) T.log.info(s"Overwriting BSP connection file: ${bspFile}")
      else T.log.info(s"Creating BSP connection file: ${bspFile}")
      val withDebug = T.log.debugEnabled
      if (withDebug) T.log.debug(
        "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
      )
      os.write.over(bspFile, createBspConnectionJson(jobs, withDebug), createFolders = true)
    }

  @deprecated("Use other overload instead.", "Mill after 0.10.7")
  def createBspConnectionJson(jobs: Int): String =
    BSP.createBspConnectionJson(jobs: Int, debug = false)

  // creates a Json with the BSP connection details
  def createBspConnectionJson(jobs: Int, debug: Boolean): String = {
    // we assume, the classpath is an executable jar here, FIXME
    val props = sys.props
    val millPath = props
      .get("mill.main.cli")
      .orElse(props.get("java.class.path"))
      .getOrElse(throw new IllegalStateException("System property 'java.class.path' not set"))

    write(
      BspConfigJson(
        name = "mill-bsp",
        argv = Seq(
          millPath,
          "--bsp",
          "--disable-ticker",
          "--color",
          "false",
          "--jobs",
          s"${jobs}"
        ) ++ (if (debug) Seq("--debug") else Seq()),
        millVersion = BuildInfo.millVersion,
        bspVersion = bspProtocolVersion,
        languages = languages
      )
    )
  }

  /**
   * Computes a mill command which starts the mill-bsp
   * server and establishes connection to client. Waits
   * until a client connects and ends the connection
   * after the client sent an "exit" notification
   *
   * @param ev Environment, used by mill to evaluate commands
   * @return: mill.Command which executes the starting of the
   *          server
   */
  def start(ev: Evaluator): Command[BspServerResult] = T.command {
    startBspServer(
      initialEvaluator = Some(ev),
      outStream = MillMain.initialSystemStreams.out,
      errStream = T.log.errorStream,
      inStream = MillMain.initialSystemStreams.in,
      logDir = ev.rootModule.millSourcePath / ".bsp",
      canReload = false
    )
  }

  /**
   * This command only starts a BSP session, which means it injects the current evaluator into an already running BSP server.
   * This command requires Mill to start with `--bsp` option.
   * @param ev The Evaluator
   * @return The server result, indicating if mill should re-run this command or just exit.
   */
  def startSession(ev: Evaluator): Command[BspServerResult] = T.command {
    T.log.errorStream.println("BSP/startSession: Starting BSP session")
    val serverHandle: BspServerHandle = Await.result(millServerHandle.future, Duration.Inf)
    val res = serverHandle.runSession(ev)
    T.log.errorStream.println(s"BSP/startSession: Finished BSP session, result: ${res}")
    res
  }

  def startBspServer(
      initialEvaluator: Option[Evaluator],
      outStream: PrintStream,
      errStream: PrintStream,
      inStream: InputStream,
      logDir: os.Path,
      canReload: Boolean,
      serverHandle: Option[Promise[BspServerHandle]] = None
  ) = {
    val evaluator = initialEvaluator.map(_.withFailFast(false))

    val millServer =
      new MillBuildServer(
        initialEvaluator = evaluator,
        bspVersion = bspProtocolVersion,
        serverVersion = BuildInfo.millVersion,
        serverName = serverName,
        logStream = errStream,
        canReload = canReload
      ) with MillJvmBuildServer with MillJavaBuildServer with MillScalaBuildServer

    val executor = Executors.newCachedThreadPool()

    var shutdownRequestedBeforeExit = false

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(outStream)
        .setInput(inStream)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter(
          (logDir / s"${serverName}.trace").toIO
        ))
        .setExecutorService(executor)
        .create()
      millServer.onConnectWithClient(launcher.getRemoteProxy)
      val listening = launcher.startListening()
      millServer.cancellator = shutdownBefore => {
        shutdownRequestedBeforeExit = shutdownBefore
        listening.cancel(true)
      }

      val bspServerHandle = new BspServerHandle {
        private[this] var _lastResult: Option[BspServerResult] = None

        override def runSession(evaluator: Evaluator): BspServerResult = {
          _lastResult = None
          millServer.updateEvaluator(Option(evaluator))
          val onReload = Promise[BspServerResult]()
          millServer.onSessionEnd = Some { serverResult =>
            if (!onReload.isCompleted) {
              errStream.println("Unsetting evaluator on session end")
              millServer.updateEvaluator(None)
              _lastResult = Some(serverResult)
              onReload.success(serverResult)
            }
          }
          Await.result(onReload.future, Duration.Inf).tap { r =>
            errStream.println(s"Reload finished, result: ${r}")
            _lastResult = Some(r)
          }
        }

        override def lastResult: Option[BspServerResult] = _lastResult

        override def stop(): Unit = {
          errStream.println("Stopping server via handle...")
          listening.cancel(true)
        }
      }
      millServerHandle.success(bspServerHandle)
      serverHandle.foreach(_.success(bspServerHandle))

      listening.get()
      ()
    } catch {
      case _: CancellationException =>
        errStream.println("The mill server was shut down.")
      case e: Exception =>
        errStream.println(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace}""".stripMargin
        )
    } finally {
      errStream.println("Shutting down executor")
      executor.shutdown()

    }

    val finalReuslt =
      if (shutdownRequestedBeforeExit) BspServerResult.Shutdown
      else BspServerResult.Failure

    finalReuslt
  }
}
