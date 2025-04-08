package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.runner.api.{BspServerHandle, BspServerResult}
import mill.bsp.{Constants}
import mill.bsp.{BspClasspathWorker, Constants}
import mill.runner.api.{Result, SystemStreams}
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.{PrintStream, PrintWriter}
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, CancellationException, Promise}

private class BspWorkerImpl() extends BspClasspathWorker {

  override def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean
  ): mill.runner.api.Result[BspServerHandle] = {

    try {
      lazy val millServer: MillBuildServer with MillJvmBuildServer with MillJavaBuildServer
        with MillScalaBuildServer =
        new MillBuildServer(
          topLevelProjectRoot = topLevelBuildRoot,
          bspVersion = Constants.bspProtocolVersion,
          serverVersion = BuildInfo.millVersion,
          serverName = Constants.serverName,
          logStream = logStream,
          canReload = canReload,
          debugMessages = Option(System.getenv("MILL_BSP_DEBUG")).contains("true"),
          onShutdown = () => {
            listening.cancel(true)
          }
        ) with MillJvmBuildServer with MillJavaBuildServer with MillScalaBuildServer

      lazy val executor = Executors.newCachedThreadPool()
      lazy val launcher = new Launcher.Builder[BuildClient]()
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
      lazy val listening = launcher.startListening()

      val bspServerHandle = new BspServerHandle {
        override def runSession(evaluators: Seq[mill.runner.api.EvaluatorApi]): BspServerResult = {
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
        listening.get()
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
