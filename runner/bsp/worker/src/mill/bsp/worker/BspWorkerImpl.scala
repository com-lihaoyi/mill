package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.api.internal.{BspServerHandle, BspServerResult, EvaluatorApi}
import mill.bsp.Constants
import mill.api.{Logger, Result, SystemStreams}
import mill.client.lock.Lock
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.PrintWriter
import java.util.concurrent.Executors
import scala.concurrent.{CancellationException, ExecutionContext}

object BspWorkerImpl {

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      outLock: Lock,
      baseLogger: Logger
  ): mill.api.Result[BspServerHandle] = {

    try {
      val executor = Executors.newCachedThreadPool()
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
      lazy val millServer
          : MillBuildServer & MillJvmBuildServer & MillJavaBuildServer & MillScalaBuildServer =
        new MillBuildServer(
          topLevelProjectRoot = topLevelBuildRoot,
          bspVersion = Constants.bspProtocolVersion,
          serverVersion = BuildInfo.millVersion,
          serverName = Constants.serverName,
          logStream = streams.err,
          canReload = canReload,
          debugMessages = Option(System.getenv("MILL_BSP_DEBUG")).contains("true"),
          onShutdown = () => listening.cancel(true),
          outLock = outLock,
          baseLogger = baseLogger
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
        override def runSession(evaluators: Seq[EvaluatorApi]): BspServerResult = {
          millServer.updateEvaluator(Option(evaluators))
          millServer.sessionResult = None
          while (millServer.sessionResult.isEmpty) Thread.sleep(1)
          millServer.updateEvaluator(None)
          val res = millServer.sessionResult.get
          streams.err.println(s"Reload finished, result: $res")
          res
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
