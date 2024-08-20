package mill.testkit

import mill.eval.Evaluator
import mill.resolve.SelectMode
import os.{Path, Shellable}
import utest._
import collection.mutable
import scala.util.control.NonFatal

object IntegrationTestSuite {
  case class EvalResult(isSuccess: Boolean, out: String, err: String)
}


abstract class IntegrationTestSuite extends TestSuite {
  val integrationTestMode: String = sys.env("MILL_INTEGRATION_TEST_MODE")
  assert(Set("local", "fork", "server").contains(integrationTestMode))

  def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???))

  def scriptSourcePath: os.Path = os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))

  def buildPath: os.SubPath = os.sub / "build.sc"

  def wd: Path = workspacePath / buildPath / os.up

  val debugLog = false

  def eval(
             cmd: os.Shellable,
             env: Map[String, String] = millTestSuiteEnv,
             cwd: os.Path = wd,
             stdin: os.ProcessInput = os.Pipe,
             stdout: os.ProcessOutput = os.Pipe,
             stderr: os.ProcessOutput = os.Pipe,
             mergeErrIntoOut: Boolean = false,
             timeout: Long = -1,
             check: Boolean = false,
             propagateEnv: Boolean = true,
             timeoutGracePeriod: Long = 100
           ): IntegrationTestSuite.EvalResult = {
    val serverArgs =
      if (integrationTestMode == "server" || integrationTestMode == "local") Seq()
      else Seq("--no-server")

    val debugArgs = if (debugLog) Seq("--debug") else Seq()

    val shellable: os.Shellable = (millReleaseFileOpt.get, serverArgs, debugArgs, cmd, wd)
    val res0 = os.call(
      cmd = shellable,
      env = env,
      cwd = cwd,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      mergeErrIntoOut = mergeErrIntoOut,
      timeout = timeout,
      check = check,
      propagateEnv = propagateEnv,
      timeoutGracePeriod = timeoutGracePeriod,
    )

    IntegrationTestSuite.EvalResult(res0.exitCode == 0, res0.out.text(), res0.err.text())
  }

  val millReleaseFileOpt: Option[Path] =
    Option(System.getenv("MILL_TEST_LAUNCHER")).map(os.Path(_, os.pwd))

  val millTestSuiteEnv: Map[String, String] = Map("MILL_TEST_SUITE" -> this.getClass().toString())

  def meta(s: String): String = {
    val Seq((List(selector), _)) =
      mill.resolve.ParseArgs.apply(Seq(s), SelectMode.Separated).getOrElse(???)

    val segments = selector._2.value.flatMap(_.pathSegments)
    os.read(wd / "out" / segments.init / s"${segments.last}.json")
  }

  def metaCached(selector: String): Evaluator.Cached = {
    val data = meta(selector)
    upickle.default.read[Evaluator.Cached](data)
  }

  def metaValue[T: upickle.default.Reader](selector: String): T = {
    val cached = metaCached(selector)
    upickle.default.read[T](cached.value)
  }

  def initWorkspace(): Path = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    // somehow os.copy does not properly preserve symlinks
    // os.copy(scriptSourcePath, workspacePath)
    os.proc("cp", "-R", scriptSourcePath, workspacePath).call()

    os.remove.all(workspacePath / "out")
    workspacePath
  }

  def mangleFile(p: os.Path, f: String => String): Unit = os.write.over(p, f(os.read(p)))

  override def utestAfterEach(path: Seq[String]): Unit = {
    if (integrationTestMode == "server" || integrationTestMode == "local") {
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
