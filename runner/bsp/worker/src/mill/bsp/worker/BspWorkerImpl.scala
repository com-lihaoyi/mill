package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.bsp.BuildInfo
import mill.api.internal.{BspServerHandle, BspServerResult, EvaluatorApi}
import mill.bsp.Constants
import mill.api.{Logger, Result, SystemStreams}
import mill.client.lock.Lock
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import scala.concurrent.{Await, CancellationException, Promise}
import scala.concurrent.duration.Duration

object BspWorkerImpl {

  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean,
      outLock: Lock,
      baseLogger: Logger,
      out: os.Path
  ): mill.api.Result[BspServerHandle] = {

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

      millServer.onConnectWithClient(launcher.getRemoteProxy)
      lazy val listening = launcher.startListening()

      val bspServerHandle = new BspServerHandle {
        override def runSession(evaluators: Seq[EvaluatorApi]): BspServerResult = {
          millServer.updateEvaluator(Some(evaluators))
          millServer.sessionResult = Promise()
          val res = Await.result(millServer.sessionResult.future, Duration.Inf)
          millServer.updateEvaluator(None)
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
