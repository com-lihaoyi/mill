package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.api.daemon.internal.EvaluatorApi
import mill.bsp.Constants
import mill.api.{Logger, Result, SystemStreams}
import mill.client.lock.Lock
import mill.api.daemon.Watchable
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import scala.concurrent.{CancellationException, Future, Promise}
import mill.api.daemon.internal.bsp.{BspServerHandle, BspServerResult}

object BspWorkerImpl {

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      outLock: Lock,
      baseLogger: Logger,
      out: os.Path
  ): mill.api.Result[(BspServerHandle, BuildClient)] = {

    try {
      val executor = createJsonrpcExecutor()
      lazy val millServer
          : MillBuildServer & MillJvmBuildServer & MillJavaBuildServer & MillScalaBuildServer =
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
          outLock = outLock,
          baseLogger = baseLogger,
          out = out
        ) with MillJvmBuildServer with MillJavaBuildServer with MillScalaBuildServer

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
        override def startSession(
            evaluators: Seq[EvaluatorApi],
            errored: Boolean,
            watched: Seq[Watchable]
        ): Future[BspServerResult] = {
          // FIXME We might be losing some shutdown requests here
          val sessionResultPromise = Promise[BspServerResult]()
          millServer.sessionResult = sessionResultPromise
          millServer.updateEvaluator(evaluators, errored = errored, watched = watched)
          sessionResultPromise.future
        }

        override def resetSession(): Unit = {
          millServer.resetEvaluator()
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
