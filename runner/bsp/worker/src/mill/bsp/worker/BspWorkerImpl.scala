package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.bsp.Constants
import mill.api.{Logger, Result, SystemStreams}
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import scala.concurrent.{CancellationException, Future}
import mill.api.daemon.internal.bsp.{
  BspBootstrapBridge,
  BspServerHandle,
  BspServerResult
}

object BspWorkerImpl {

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      baseLogger: Logger,
      out: os.Path,
      noWaitForBspLock: Boolean,
      killOther: Boolean,
      bspWatch: Boolean,
      bootstrapBridge: BspBootstrapBridge
  ): mill.api.Result[(BspServerHandle, BuildClient)] = {

    try {
      val executor = createJsonrpcExecutor()
      lazy val millServer
          : MillBuildServer & EndpointsJvm & EndpointsJava & EndpointsScala =
        new MillBuildServer(
          topLevelProjectRoot = topLevelBuildRoot,
          bspVersion = Constants.bspProtocolVersion,
          serverVersion = BuildInfo.millVersion,
          serverName = Constants.serverName,
          canReload = canReload,
          onShutdown = () => {
            listening.cancel(true)
            executor.shutdown()
          },
          baseLogger = baseLogger,
          out = out,
          noWaitForBspLock = noWaitForBspLock,
          killOther = killOther,
          bspWatch = bspWatch,
          bootstrapBridge = bootstrapBridge
        ) with EndpointsJvm
          with EndpointsJava
          with EndpointsScala
          with MillBspEndpoints

      lazy val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(streams.out)
        .setInput(streams.in)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter((logDir / "trace.log").toIO))
        .setExecutorService(executor)
        .create()

      lazy val listening = launcher.startListening()
      val client = launcher.getRemoteProxy
      millServer.onConnectWithClient(client)

      val bspServerHandle = new BspServerHandle {
        override def shutdownFuture: Future[BspServerResult] =
          millServer.shutdownPromise.future

        override def close(): Unit = {
          streams.err.println("Stopping server via handle...")
          listening.cancel(true)
          millServer.close()
        }
      }

      mill.api.daemon.StartThread("BSP-Shutdown-Listener-Thread", daemon = true) {
        try listening.get()
        catch {
          case _: CancellationException => // normal exit
          case t: Throwable =>
            streams.err.println(s"BSP listener exited with error: $t")
        }
        streams.err.println("Shutting down executor")
        executor.shutdown()
      }

      Result.Success((bspServerHandle, client))
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

  private val executorCounter = new AtomicInteger
  private def createJsonrpcExecutor(): ExecutorService =
    Executors.newCachedThreadPool(
      new ThreadFactory {
        val executorCount = executorCounter.incrementAndGet()
        val counter = new AtomicInteger
        def newThread(runnable: Runnable): Thread = {
          val t = new Thread(
            runnable,
            s"mill-bsp-jsonrpc-$executorCount-thread-${counter.incrementAndGet()}"
          )
          t.setDaemon(true)
          t
        }
      }
    )
}
