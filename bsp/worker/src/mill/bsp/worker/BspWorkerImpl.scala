package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.api.internal.{BspServerHandle, BspServerResult, EvaluatorApi}
import mill.bsp.Constants
import mill.api.{Result, SystemStreams}
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.PrintWriter
import java.util.concurrent.Executors
import scala.concurrent.{CancellationException, ExecutionContext, Future, Promise}

object BspWorkerImpl {

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean
  ): mill.api.Result[BspServerHandle] = {

    try {
      val executor = Executors.newCachedThreadPool()
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
      lazy val millServer: MillBuildServer with MillJvmBuildServer with MillJavaBuildServer
        with MillScalaBuildServer =
        new MillBuildServer(
          topLevelProjectRoot = topLevelBuildRoot,
          bspVersion = Constants.bspProtocolVersion,
          serverVersion = BuildInfo.millVersion,
          serverName = Constants.serverName,
          logStream = streams.err,
          canReload = canReload,
          debugMessages = Option(System.getenv("MILL_BSP_DEBUG")).contains("true"),
          onShutdown = () => listening.cancel(true)
        ) with MillJvmBuildServer with MillJavaBuildServer with MillScalaBuildServer

      lazy val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(streams.out)
        .setInput(streams.in)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter((logDir / "trace.log").toIO))
        .setExecutorService(executor)
        .create()

      millServer.onConnectWithClient(launcher.getRemoteProxy)
      lazy val listening = launcher.startListening()

      val bspServerHandle = new BspServerHandle {
        override def startSession(
            evaluators: Seq[EvaluatorApi],
            errored: Boolean
        ): Future[BspServerResult] = {
          val sessionResultPromise = Promise[BspServerResult]()
          millServer.sessionResult = sessionResultPromise
          millServer.updateEvaluator(Option(evaluators), errored = errored)
          sessionResultPromise.future
        }

        override def close(): Unit = {
          streams.err.println("Stopping server via handle...")
          listening.cancel(true)
        }
      }

      new Thread(() => {
        try listening.get()
        catch {
          case _: CancellationException => // normal exit
        }
        streams.err.println("Shutting down executor")
        executor.shutdown()
      }).start()

      Result.Success(bspServerHandle)
    } catch {
      case _: CancellationException =>
        Result.Failure("The mill server was shut down.")
      case e: Exception =>
        Result.Failure(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace.mkString("\n")}""".stripMargin
        )
    }
  }
}
