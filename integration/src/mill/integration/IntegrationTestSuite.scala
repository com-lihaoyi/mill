package mill.integration

import mainargs.Flag
import mill.MillCliConfig
import mill.define.SelectMode
import mill.runner.{MillBuildBootstrap, MillMain, RunnerState, Watching}
import mill.util.SystemStreams
import os.Path
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.file.NoSuchFileException
import scala.util.control.NonFatal

object IntegrationTestSuite{
  case class EvalResult(isSuccess: Boolean, out: String, err: String)
}
abstract class IntegrationTestSuite(
    val scriptSlug: String,
    fork: Boolean,
    clientServer: Boolean = false,
    workspaceSlug: Option[String] = None,
) extends TestSuite{

  val finalWorkspaceSlug = workspaceSlug.getOrElse(scriptSlug)
  def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / os.RelPath(finalWorkspaceSlug)

  def scriptSourcePath: os.Path =
    os.pwd / "integration" / "resources" / os.RelPath(scriptSlug)

  def buildPath: os.SubPath = os.sub / "build.sc"

  def wd = workspacePath / buildPath / os.up

  val stdIn = new ByteArrayInputStream(Array())
  val disableTicker = false
  val debugLog = false
  val keepGoing = false
  val userSpecifiedProperties = Map[String, String]()
  val threadCount = sys.props.get("MILL_THREAD_COUNT").map(_.toInt).orElse(Some(1))

  var runnerState = RunnerState.empty

  private def runnerStdout(stdout: PrintStream, stderr: PrintStream, s: Seq[String]) = {

    val streams = new SystemStreams(stdout, stderr, stdIn)
    val config = MillCliConfig(
      debugLog = Flag(debugLog),
      keepGoing = Flag(keepGoing),
      disableTicker = Flag(disableTicker)
    )
    val logger = mill.runner.MillMain.getLogger(
      streams,
      config,
      mainInteractive = false,
      enableTicker = Some(false)
    )

    val (isSuccess, newRunnerState) = Watching.watchLoop(
      logger = logger,
      ringBell = config.ringBell.value,
      watch = config.watch.value,
      streams = streams,
      setIdle = _ => (),
      evaluate = (prevStateOpt: Option[RunnerState]) => {
        MillMain.adjustJvmProperties(userSpecifiedProperties, sys.props.toMap)
        new MillBuildBootstrap(
          projectRoot = wd,
          config = config,
          env = Map.empty,
          threadCount = threadCount,
          targetsAndParams = s.toList,
          prevState = runnerState,
          logger = logger,
        ).evaluate()
      },
      watchedPathsFile = wd / "out" / "mill-watched-paths.txt"
    )

    runnerState = newRunnerState

    (isSuccess, newRunnerState)
  }

  def eval(s: String*): Boolean = {
    if (!fork) runnerStdout(System.out, System.err, s)._1
    else evalFork(os.Inherit, os.Inherit, s)
  }

  def evalStdout(s: String*): IntegrationTestSuite.EvalResult = {
    if (!fork) {
      val outputStream = new ByteArrayOutputStream
      val errorStream = new ByteArrayOutputStream
      val printOutStream = new PrintStream(outputStream)
      val printErrStream = new PrintStream(errorStream)
      val result = runnerStdout(printOutStream, printErrStream, s.toList)
      val stdoutArray = outputStream.toByteArray
      val stdout: Seq[String] =
        if (stdoutArray.isEmpty) Seq.empty
        else new String(stdoutArray).split('\n')
      val stderrArray = errorStream.toByteArray
      val stderr: Seq[String] =
        if (stderrArray.isEmpty) Seq.empty
        else new String(stderrArray).split('\n')
      IntegrationTestSuite.EvalResult(result._1, stdout.mkString("\n"), stderr.mkString("\n"))
    } else {
      val output = Seq.newBuilder[String]
      val error = Seq.newBuilder[String]
      val processOutput = os.ProcessOutput.Readlines(output += _)
      val processError = os.ProcessOutput.Readlines(error += _)

      val result = evalFork(processOutput, processError, s)
      IntegrationTestSuite.EvalResult(result, output.result().mkString("\n"), error.result().mkString("\n"))
    }
  }

  val millReleaseFileOpt = Option(System.getenv("MILL_TEST_RELEASE")).map(os.Path(_, os.pwd))
  val millTestSuiteEnv = Map("MILL_TEST_SUITE" -> this.getClass().toString())

  private def evalFork(stdout: os.ProcessOutput, stderr: os.ProcessOutput, s: Seq[String]): Boolean = {
    val extraArgs = if (clientServer) Seq() else Seq("--no-server")

    try {
      os.proc(millReleaseFileOpt.get, extraArgs, s).call(
        cwd = wd,
        stdin = os.Inherit,
        stdout = stdout,
        stderr = stderr,
        env = millTestSuiteEnv
      )
      true
    } catch {
      case NonFatal(_) => false
    }
  }

  def meta(s: String): String = {
    val Seq((List(selector), _)) =
      mill.define.ParseArgs.apply(Seq(s), SelectMode.Single).getOrElse(???)

    val segments = selector._2.value.flatMap(_.pathSegments)
    os.read(wd / "out" / segments.init / s"${segments.last}.json")
  }

  def initWorkspace(): Path = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    os.copy(scriptSourcePath, workspacePath)
    workspacePath
  }

  override def utestAfterEach(path: Seq[String]): Unit = {
    runnerState = RunnerState.empty
    if (clientServer) {
      // try to stop the server
      try {
        os.proc(millReleaseFileOpt.get, "shutdown").call(
          cwd = wd,
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit,
          env = millTestSuiteEnv
        )
      } catch {
        case NonFatal(e) =>
      }
    }
  }
}
