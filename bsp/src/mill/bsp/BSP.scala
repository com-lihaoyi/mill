package mill.bsp

import ch.epfl.scala.bsp4j.BuildClient
import java.io.PrintWriter
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executors
import mill.{BuildInfo, T}
import mill.bsp.{BspConfigJson, MillBuildServer}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.modules.Util
import org.eclipse.lsp4j.jsonrpc.Launcher
import scala.concurrent.CancellationException
import upickle.default.write

object BSP extends ExternalModule {
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[this.type] = Discover[this.type]
  val bspProtocolVersion = "2.0.0"
  val languages = Seq("scala", "java")

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
   *
   */
  def install(evaluator: Evaluator): Command[Unit] =
    T.command {
      val bspDirectory = evaluator.rootModule.millSourcePath / ".bsp"
      if (!os.exists(bspDirectory)) os.makeDir.all(bspDirectory)
      try {
        os.write(bspDirectory / "mill.json", createBspConnectionJson())
      } catch {
        case _: FileAlreadyExistsException =>
          T.log.info(
            "The bsp connection json file probably exists already - will be overwritten")
          os.remove(bspDirectory / "mill.json")
          os.write(bspDirectory / "mill.json", createBspConnectionJson())
        case e: Exception =>
          T.log.error("An exception occurred while installing mill-bsp")
          e.printStackTrace()
      }

    }

  // creates a Json with the BSP connection details
  def createBspConnectionJson(): String = {
    val millPath = sys.props
      .get("java.class.path")
      .getOrElse(throw new IllegalStateException(
        "System property java.class.path not set"))

    write(
      BspConfigJson(
        name = "mill-bsp",
        argv = Seq(millPath, "-i", s"${BSP.getClass.getCanonicalName.split("[$]").head}/start"),
        millVersion = Util.millProperty("MILL_VERSION").getOrElse(BuildInfo.millVersion),
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
  def start(ev: Evaluator): Command[Unit] =
    T.command {
      val evaluator = new Evaluator(
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
      val millServer = new MillBuildServer(
        evaluator,
        bspProtocolVersion,
        BuildInfo.millVersion)
      val executor = Executors.newCachedThreadPool()

      val stdin = System.in
      val stdout = System.out
      try {
        val launcher = new Launcher.Builder[BuildClient]()
          .setOutput(stdout)
          .setInput(stdin)
          .setLocalService(millServer)
          .setRemoteInterface(classOf[BuildClient])
          .traceMessages(new PrintWriter(
            (evaluator.rootModule.millSourcePath / ".bsp" / "mill.log").toIO))
          .setExecutorService(executor)
          .create()
        millServer.onConnectWithClient(launcher.getRemoteProxy)
        val listening = launcher.startListening()
        millServer.cancelator = () => listening.cancel(true)
        listening.get()
        ()
      } catch {
        case _: CancellationException =>
          T.log.error("The mill server was shut down.")
        case e: Exception =>
          T.log.error(
            s"""An exception occured while connecting to the client.
               |Cause: ${e.getCause}
               |Message: ${e.getMessage}
               |Exception class: ${e.getClass}
               |Stack Trace: ${e.getStackTrace}""".stripMargin
          )
      } finally {
        T.log.error("Shutting down executor")
        executor.shutdown()
      }
    }
}
