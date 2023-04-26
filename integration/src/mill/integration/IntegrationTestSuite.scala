package mill.integration

import mainargs.Flag
import mill.runner.MillCliConfig
import mill.define.SelectMode
import mill.runner.{MillBuildBootstrap, MillMain, RunnerState, Watching}
import mill.api.SystemStreams
import mill.util.PrintLogger
import os.Path
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.file.NoSuchFileException
import scala.util.control.NonFatal

object IntegrationTestSuite {
  case class EvalResult(isSuccess: Boolean, out: String, err: String)

}

abstract class IntegrationTestSuite extends TestSuite {
  val scriptSlug: String = sys.env("MILL_INTEGRATION_TEST_SLUG")

  val integrationTestMode: String = sys.env("MILL_INTEGRATION_TEST_MODE")
  assert(Set("local", "fork", "server").contains(integrationTestMode))

  def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???))

  def scriptSourcePath: os.Path = os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))

  def buildPath: os.SubPath = os.sub / "build.sc"

  def wd = workspacePath / buildPath / os.up

  val stdIn = new ByteArrayInputStream(Array())
  val disableTicker = false
  val debugLog = false
  val keepGoing = false
  val userSpecifiedProperties = Map[String, String]()
  val threadCount = sys.props.get("MILL_THREAD_COUNT").map(_.toInt).orElse(Some(1))

  var runnerState = RunnerState.empty

  private def runnerStdout(env: Map[String, String], stdout: PrintStream, stderr: PrintStream, s: Seq[String]) = {
    val streams = new SystemStreams(stdout, stderr, stdIn)
    SystemStreams.withStreams(streams) {
      val config = MillCliConfig(
        debugLog = Flag(debugLog),
        keepGoing = Flag(keepGoing),
        disableTicker = Flag(disableTicker)
      )
      val logger = mill.runner.MillMain.getLogger(
        streams,
        config,
        mainInteractive = false,
        enableTicker = Some(false),
        new PrintLogger.State()
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
            env = env,
            threadCount = threadCount,
            targetsAndParams = s.toList,
            prevRunnerState = runnerState,
            logger = logger
          ).evaluate()
        }
      )

      runnerState = newRunnerState
      (isSuccess, newRunnerState)
    }
  }

  def eval(s: String*): Boolean = {
    if (integrationTestMode == "local") runnerStdout(Map(), System.out, System.err, s)._1
    else evalFork(os.Inherit, os.Inherit, s)
  }

  class ByteArrayOutputPrintStreams() {
    val byteArrayOutputStream = new ByteArrayOutputStream
    val printStream = new PrintStream(byteArrayOutputStream)
    def text() = byteArrayOutputStream.toString("UTF-8").linesIterator.mkString("\n")
  }

  def evalStdout(s: String*): IntegrationTestSuite.EvalResult = evalEnvStdout(Map(), s: _*)

  def evalEnvStdout(env: Map[String, String], s: String*): IntegrationTestSuite.EvalResult = {
    if (integrationTestMode == "local") {
      val outputStream = new ByteArrayOutputPrintStreams()
      val errorStream = new ByteArrayOutputPrintStreams()
      val result = runnerStdout(env, outputStream.printStream, errorStream.printStream, s.toList)

      IntegrationTestSuite.EvalResult(result._1, outputStream.text(), errorStream.text())
    } else {
      val output = Seq.newBuilder[String]
      val error = Seq.newBuilder[String]
      val processOutput = os.ProcessOutput.Readlines(output += _)
      val processError = os.ProcessOutput.Readlines(error += _)

      val result = evalFork(processOutput, processError, s)
      IntegrationTestSuite.EvalResult(
        result,
        output.result().mkString("\n"),
        error.result().mkString("\n")
      )
    }
  }

  val millReleaseFileOpt = Option(System.getenv("MILL_TEST_RELEASE")).map(os.Path(_, os.pwd))
  val millTestSuiteEnv = Map("MILL_TEST_SUITE" -> this.getClass().toString())

  private def evalFork(
      stdout: os.ProcessOutput,
      stderr: os.ProcessOutput,
      s: Seq[String]
  ): Boolean = {
    val serverArgs = if (integrationTestMode == "server") Seq() else Seq("--no-server")
    val debugArgs = if (debugLog) Seq("--debug") else Seq()
    try {
      os.proc(millReleaseFileOpt.get, serverArgs, debugArgs, s).call(
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
    os.remove.all(workspacePath / "out")
    workspacePath
  }

  def mangleFile(p: os.Path, f: String => String) = os.write.over(p, f(os.read(p)))

  override def utestAfterEach(path: Seq[String]): Unit = {
    runnerState = RunnerState.empty
    if (integrationTestMode == "server") {
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
