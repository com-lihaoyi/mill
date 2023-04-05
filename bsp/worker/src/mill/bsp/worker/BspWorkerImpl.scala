package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.api.{Ctx, PathRef, internal}
import mill.{Agg, T, BuildInfo => MillBuildInfo}
import mill.bsp.{BSP, BspWorker, Constants}
import mill.define.Task
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult}
import mill.api.SystemStreams
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.{InputStream, PrintStream, PrintWriter}
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, CancellationException, Promise}
import scala.util.chaining.scalaUtilChainingOps

@internal
class BspWorkerImpl() extends BspWorker {

  def bspConnectionJson(jobs: Int, debug: Boolean): String = {
    val props = sys.props
    val millPath = props
      .get("mill.main.cli")
      // we assume, the classpath is an executable jar here
      .orElse(props.get("java.class.path"))
      .getOrElse(throw new IllegalStateException("System property 'java.class.path' not set"))

    upickle.default.write(
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
        millVersion = MillBuildInfo.millVersion,
        bspVersion = Constants.bspProtocolVersion,
        languages = Constants.languages
      )
    )
  }

  override def createBspConnection(
      jobs: Int,
      serverName: String
  )(implicit ctx: Ctx): (PathRef, String) = {
    // we create a json connection file
    val bspFile = ctx.workspace / Constants.bspDir / s"${serverName}.json"
    if (os.exists(bspFile)) ctx.log.info(s"Overwriting BSP connection file: ${bspFile}")
    else ctx.log.info(s"Creating BSP connection file: ${bspFile}")
    val withDebug = ctx.log.debugEnabled
    if (withDebug) ctx.log.debug(
      "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
    )
    val connectionContent = bspConnectionJson(jobs, withDebug)
    os.write.over(bspFile, connectionContent, createFolders = true)
    (PathRef(bspFile), connectionContent)
  }

  override def startBspServer(
      initialEvaluator: Option[Evaluator],
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      serverHandles: Seq[Promise[BspServerHandle]]
  ): BspServerResult = {
    val evaluator = initialEvaluator.map(_.withFailFast(false))

    val millServer =
      new MillBuildServer(
        initialEvaluator = evaluator,
        bspVersion = Constants.bspProtocolVersion,
        serverVersion = MillBuildInfo.millVersion,
        serverName = Constants.serverName,
        logStream = streams.bspLog.getOrElse(streams.err),
        canReload = canReload
      ) with MillJvmBuildServer with MillJavaBuildServer with MillScalaBuildServer

    val executor = Executors.newCachedThreadPool()

    var shutdownRequestedBeforeExit = false

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(streams.out)
        .setInput(streams.in)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter(
          (logDir / s"${Constants.serverName}.trace").toIO
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
              streams.err.println("Unsetting evaluator on session end")
              millServer.updateEvaluator(None)
              _lastResult = Some(serverResult)
              onReload.success(serverResult)
            }
          }
          Await.result(onReload.future, Duration.Inf).tap { r =>
            streams.err.println(s"Reload finished, result: ${r}")
            _lastResult = Some(r)
          }
        }

        override def lastResult: Option[BspServerResult] = _lastResult

        override def stop(): Unit = {
          streams.err.println("Stopping server via handle...")
          listening.cancel(true)
        }
      }
      serverHandles.foreach(_.success(bspServerHandle))

      listening.get()
      ()
    } catch {
      case _: CancellationException =>
        streams.err.println("The mill server was shut down.")
      case e: Exception =>
        streams.err.println(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace}""".stripMargin
        )
    } finally {
      streams.err.println("Shutting down executor")
      executor.shutdown()

    }

    val finalReuslt =
      if (shutdownRequestedBeforeExit) BspServerResult.Shutdown
      else BspServerResult.Failure

    finalReuslt
  }
}
