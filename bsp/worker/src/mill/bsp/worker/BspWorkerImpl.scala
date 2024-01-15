package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildClient
import mill.main.BuildInfo
import mill.bsp.{BspServerHandle, BspServerResult, BspUtil, BspWorker, Constants}
import mill.eval.Evaluator
import mill.api.SystemStreams
import mill.bsp.BspUtil.pretty
import mill.util.EitherOps
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.{PrintStream, PrintWriter}
import java.lang.reflect.Constructor
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, CancellationException, Promise}
import scala.jdk.CollectionConverters._

private class BspWorkerImpl() extends BspWorker {

  def loadService(className: String, services: Seq[AnyRef]): AnyRef = {
    val cl = getClass().getClassLoader()
    val serviceClass = cl.loadClass(className)
    val ctrWithParams: Seq[Either[String, (Constructor[_], Seq[AnyRef])]] =
      serviceClass.getConstructors().toSeq.map { ctr =>
        val ctrParams: Seq[Either[String, AnyRef]] =
          ctr.getParameterTypes().toSeq.map { paramType =>
            services.find(s => paramType.isAssignableFrom(s.getClass())).toRight(
              s"Could not inject constructor parameter of type `${paramType}`"
            )
          }
        EitherOps.sequence(ctrParams).map(p => (ctr, p))
      }
    ctrWithParams.collectFirst {
      case Right((ctr, params)) =>
        ctr.newInstance(params: _*).asInstanceOf[AnyRef]
    }.getOrElse(sys.error("Could not found acceptable contructor"))
  }

  override def startBspServer(
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean,
      projectDir: os.Path
  ): Either[String, BspServerHandle] = {

    logStream.println("Preparing BSP server start")
    os.makeDir.all(projectDir / Constants.bspDir)

    BspWorker.readConfig(projectDir).flatMap { config =>
      val millServer =
        new MillBuildServer(
          bspVersion = Constants.bspProtocolVersion,
          serverVersion = BuildInfo.millVersion,
          serverName = Constants.serverName,
          logStream = logStream,
          canReload = canReload,
          languages = config.languages
        )

      val services = config.services.map(s => s -> loadService(s, Seq(millServer))).toSeq
      logStream.println(s"Loaded services: ${BspUtil.pretty(services)}")

      val executor = Executors.newCachedThreadPool()

      var shutdownRequestedBeforeExit = false

      try {
        val launcher = new Launcher.Builder[BuildClient]()
          .setOutput(streams.out)
          .setInput(streams.in)
          .setLocalServices(services.map(_._2).asJava)
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
            streams.err.println(s"Reload finished, result: ${pretty(res)}")
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
}
