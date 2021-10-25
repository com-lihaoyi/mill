package mill.bsp

import ch.epfl.scala.bsp4j.BuildClient

import java.io.{InputStream, PrintStream, PrintWriter}
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executors
import mill.{BuildInfo, MillMain, T}
import mill.define.{Command, Discover, ExternalModule}
import mill.api.Result
import mill.eval.Evaluator
import org.eclipse.lsp4j.jsonrpc.Launcher

import scala.concurrent.CancellationException
import upickle.default.write

import scala.util.Using

object BSP extends ExternalModule {
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[this.type] = Discover[this.type]
  val bspProtocolVersion = "2.0.0"
  val languages = Seq("scala", "java")
  val serverName = "mill-bsp"

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
  def install(evaluator: Evaluator): Command[Unit] =
    T.command {
      val bspDirectory = evaluator.rootModule.millSourcePath / ".bsp"
      if (!os.exists(bspDirectory)) os.makeDir.all(bspDirectory)
      try {
        os.write(bspDirectory / s"${serverName}.json", createBspConnectionJson())
      } catch {
        case _: FileAlreadyExistsException =>
          T.log.info(
            "The bsp connection json file probably exists already - will be overwritten"
          )
          os.remove(bspDirectory / s"${serverName}.json")
          os.write(bspDirectory / s"${serverName}.json", createBspConnectionJson())
        case e: Exception =>
          T.log.error("An exception occurred while installing mill-bsp")
          e.printStackTrace(T.log.errorStream)
      }

    }

  // creates a Json with the BSP connection details
  def createBspConnectionJson(): String = {
    val millPath = sys.props
      .get("java.class.path")
      .getOrElse(throw new IllegalStateException(
        "System property java.class.path not set"
      ))

    write(
      BspConfigJson(
        name = "mill-bsp",
        argv = Seq(
          millPath,
          "--bsp",
          "--disable-ticker",
          "--color",
          "false",
          s"${BSP.getClass.getCanonicalName.split("[$]").head}/start"
        ),
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
  def start(ev: Evaluator): Command[Unit] = T.command {
    startBspServer(
      initialEvaluator = Some(ev),
      outStream = MillMain.initialSystemStreams.out,
      errStream = T.log.errorStream,
      inStream = MillMain.initialSystemStreams.in,
      projectDir = ev.rootModule.millSourcePath
    )
  }

  def startBspServer(
      initialEvaluator: Option[Evaluator],
      outStream: PrintStream,
      errStream: PrintStream,
      inStream: InputStream,
      projectDir: os.Path
  ) = {
    val evaluator = initialEvaluator.map { ev =>
      new Evaluator(
        ev.home,
        ev.outPath,
        ev.externalOutPath,
        ev.rootModule,
        ev.baseLogger,
        ev.classLoaderSig,
        ev.workerCache,
        ev.env,
        false
      )
    }

    val millServer = new MillBuildServer(
      evaluator,
      bspProtocolVersion,
      BuildInfo.millVersion,
      serverName,
      errStream
    ) with MillJavaBuildServer with MillScalaBuildServer

    val executor = Executors.newCachedThreadPool()

    var shutdownRequestedBeforeExit = false

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(outStream)
        .setInput(inStream)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter(
          (projectDir / ".bsp" / s"${serverName}.trace").toIO
        ))
        .setExecutorService(executor)
        .create()
      millServer.onConnectWithClient(launcher.getRemoteProxy)
      val listening = launcher.startListening()
      millServer.cancellator = shutdownBefore => {
        shutdownRequestedBeforeExit = shutdownBefore
        listening.cancel(true)
      }
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
    if (shutdownRequestedBeforeExit) Result.Success(())
    else Result.Failure("BSP exited without properly shutdown request")
//    }
  }
}
