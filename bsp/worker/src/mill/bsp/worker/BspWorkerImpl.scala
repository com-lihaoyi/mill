package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.main.BuildInfo
import mill.bsp.{BspServerHandle, BspServerResult, BspWorker, Constants}
import mill.eval.Evaluator
import mill.api.SystemStreams
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.{PrintStream, PrintWriter}
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, CancellationException, Promise}
import scala.jdk.CollectionConverters._

private class BspWorkerImpl() extends BspWorker {

  override def startBspServer(
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean
  ): Either[String, BspServerHandle] = {

    val millServer = new MillBuildServer(
      bspVersion = Constants.bspProtocolVersion,
      serverVersion = BuildInfo.millVersion,
      serverName = Constants.serverName,
      logStream = logStream,
      canReload = canReload
    )

    val jvmBuildServer = new MillJvmBuildServer(millServer)
    val javaBuildServer = new MillJavaBuildServer(millServer)
    val scalaBuildServer = new MillScalaBuildServer(millServer)

    val services: Seq[AnyRef] = Seq(millServer, jvmBuildServer, javaBuildServer, scalaBuildServer)

    val executor = Executors.newCachedThreadPool()

    var shutdownRequestedBeforeExit = false

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(streams.out)
        .setInput(streams.in)
        .setLocalServices(services.asJava)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter(
          (logDir / s"${Constants.serverName}.trace").toIO
        ))
        .setExecutorService(executor)
        .setClassLoader(getClass().getClassLoader())
        .create()

      millServer.onConnectWithClient(launcher.getRemoteProxy)
      val listening = launcher.startListening()
      millServer.cancellator = shutdownBefore => {
        shutdownRequestedBeforeExit = shutdownBefore
        listening.cancel(true)
      }

      val bspServerHandle = new BspServerHandle {
        private[this] var lastResult0: Option[BspServerResult] = None

        override def runSession(evaluators: Seq[Evaluator]): BspServerResult = {
          lastResult0 = None
          millServer.updateEvaluator(Option(evaluators))
          val onReload = Promise[BspServerResult]()
          millServer.onSessionEnd = Some { serverResult =>
            if (!onReload.isCompleted) {
              streams.err.println("Unsetting evaluator on session end")
              millServer.updateEvaluator(None)
              lastResult0 = Some(serverResult)
              onReload.success(serverResult)
            }
          }
          val res = Await.result(onReload.future, Duration.Inf)
          streams.err.println(s"Reload finished, result: ${res}")
          lastResult0 = Some(res)
          res
        }

        override def lastResult: Option[BspServerResult] = lastResult0

        override def stop(): Unit = {
          streams.err.println("Stopping server via handle...")
          listening.cancel(true)
        }
      }

      new Thread(() => {
        listening.get()
        streams.err.println("Shutting down executor")
        executor.shutdown()
      }).start()

      Right(bspServerHandle)
    } catch {
      case _: CancellationException =>
        Left("The mill server was shut down.")
      case e: Exception =>
        Left(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace.mkString("\n")}""".stripMargin
        )
    }
  }
}
