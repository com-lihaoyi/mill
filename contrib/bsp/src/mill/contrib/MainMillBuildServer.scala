package mill.contrib

import play.api.libs.json._
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executors
import upickle.default._
import ch.epfl.scala.bsp4j._
import mill._
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import org.eclipse.lsp4j.jsonrpc.Launcher
import scala.collection.JavaConverters._


object BSP extends ExternalModule {

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  lazy val millDiscover: Discover[BSP.this.type] = Discover[this.type]
  val version = "1.0.0"
  val bspVersion = "2.0.0"
  val languages = List("scala", "java")

  // computes the path to the java executable
  def whichJava: String = {
    if (scala.sys.props.contains("JAVA_HOME")) scala.sys.props("JAVA_HOME") else "java"
  }

  // creates a Json with the BSP connection details
  def createBspConnectionJson(): JsValue = {

    implicit val connectionWrites = new Writes[BspConnectionDetails] {
      def writes(connection: BspConnectionDetails) = Json.obj(
        "name" -> connection.getName,
        "argv" -> new JsArray(connection.getArgv.asScala.map(string => JsString(string)).toIndexedSeq),
        "version" -> connection.getVersion,
        "bspVersion" -> connection.getBspVersion,
        "languages" -> new JsArray(connection.getLanguages.asScala.map(string => JsString(string)).toIndexedSeq)
      )
    }
    val millPath = scala.sys.props("MILL_CLASSPATH")
    Json.toJson(new BspConnectionDetails("mill-bsp",
                                          List(whichJava,"-DMILL_CLASSPATH=" + millPath,
                                            s"-DMILL_VERSION=${scala.sys.props("MILL_VERSION")}",
                                            "-Djna.nosys=true", "-cp",
                                            millPath,
                                            "mill.MillMain", "mill.contrib.BSP/start").asJava,
                                          version,
                                          bspVersion,
                                          languages.asJava))
  }

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
  def install(ev: Evaluator): Command[Unit] = T.command{
    val bspDirectory = os.pwd / ".bsp"
    if (! os.exists(bspDirectory)) os.makeDir.all(bspDirectory)
    try {
      os.write(bspDirectory / "mill.json", Json.stringify(createBspConnectionJson()))
    } catch {
      case e: FileAlreadyExistsException =>
        println("The bsp connection json file probably exists already - will be overwritten")
        os.remove(bspDirectory / "mill.json")
        os.write(bspDirectory / "mill.json", Json.stringify(createBspConnectionJson()))
      case e: Exception => println("An exception occurred while installing mill-bsp: " + e.getMessage +
                                  " " + e.getStackTrace.toString)
    }

  }

  /**
    * Computes a mill command which starts the mill-bsp
    * server and establishes connection to client. Waits
    * until a client connects and ends the connection
    * after the client sent an "exit" notification
    * @param ev Environment, used by mill to evaluate commands
    * @return: mill.Command which executes the starting of the
    *         server
    */
  def start(ev: Evaluator): Command[Unit] = T.command {
    val eval = new Evaluator(ev.home, ev.outPath, ev.externalOutPath, ev.rootModule, ev.log, ev.classLoaderSig,
                    ev.workerCache, ev.env, false)
    val millServer = new mill.contrib.bsp.MillBuildServer(eval, bspVersion, version, languages)
    val executor = Executors.newCachedThreadPool()

    val stdin = System.in
    val stdout = System.out
    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(stdout)
        .setInput(stdin)
        .setLocalService(millServer)
        .setRemoteInterface(classOf[BuildClient])
        .setExecutorService(executor)
        .create()
      millServer.onConnectWithClient(launcher.getRemoteProxy)
      val listening = launcher.startListening()
      millServer.cancelator = () => listening.cancel(true)
      val voidFuture = listening.get()
    } catch {
      case e: Exception =>
        System.err.println("An exception occured while connecting to the client.")
        System.err.println("Cause: " + e.getCause)
        System.err.println("Message: " + e.getMessage)
        System.err.println("Exception class: " + e.getClass)
        System.err.println("Stack Trace: " + e.getStackTrace)
    } finally {
      System.err.println("Shutting down executor")
      executor.shutdown()
    }
  }
}