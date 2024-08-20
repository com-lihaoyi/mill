package mill.testkit

import mill.eval.Evaluator
import mill.resolve.SelectMode
import os.Path
import utest._
import scala.util.control.NonFatal
import ujson.Value

object IntegrationTestSuite {
  /**
   * A very simplified version of `os.CommandResult` meant for easily
   * performing assertions against.
   */
  case class EvalResult(isSuccess: Boolean, out: String, err: String)
}

abstract class IntegrationTestSuite extends TestSuite {

  protected val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.sc` file, any application code, and
   * the `out/` folder containing the build output
   */
  val workspacePath: os.Path = os.temp.dir(deleteOnExit = false)


  protected def scriptSourcePath: os.Path = os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))

  def debugLog = false

  /**
   * Evaluates a Mill [[cmd]]. Essentially the same as `os.call`, except it
   * provides the Mill executable and some test flags and environment variables
   * for you, and wraps the output in a [[IntegrationTestSuite.EvalResult]] for
   * convenience.
   */
  def eval(cmd: os.Shellable,
           env: Map[String, String] = millTestSuiteEnv,
           cwd: os.Path = workspacePath,
           stdin: os.ProcessInput = os.Pipe,
           stdout: os.ProcessOutput = os.Pipe,
           stderr: os.ProcessOutput = os.Pipe,
           mergeErrIntoOut: Boolean = false,
           timeout: Long = -1,
           check: Boolean = false,
           propagateEnv: Boolean = true,
           timeoutGracePeriod: Long = 100): IntegrationTestSuite.EvalResult = {
    val serverArgs = Option.when(!clientServerMode)("--no-server")

    val debugArgs = Option.when(debugLog)("--debug")

    val shellable: os.Shellable = (millReleaseFileOpt.get, serverArgs, debugArgs, cmd)
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

  private val millReleaseFileOpt: Option[Path] =
    Option(System.getenv("MILL_INTEGRATION_LAUNCHER")).map(os.Path(_, os.pwd))

  private val millTestSuiteEnv: Map[String, String] = Map("MILL_TEST_SUITE" -> this.getClass().toString())

  /**
   * Helpers to read the `.json` metadata files belonging to a particular task
   * (specified by [[selector0]]) from the `out/` folder.
   */
  def meta(selector0: String): Meta = new Meta(selector0)
  class Meta(selector0: String){
    def text: String = {
      val Seq((List(selector), _)) =
        mill.resolve.ParseArgs.apply(Seq(selector0), SelectMode.Separated).getOrElse(???)

      val segments = selector._2.value.flatMap(_.pathSegments)
      os.read(workspacePath / "out" / segments.init / s"${segments.last}.json")
    }

    def cached: Evaluator.Cached = upickle.default.read[Evaluator.Cached](text)

    def json: Value.Value = ujson.read(cached.value)

    def value[T: upickle.default.Reader]: T = upickle.default.read[T](cached.value)
  }

  def initWorkspace(): Unit = {
    println(s"Copying integration test sources from $scriptSourcePath to $workspacePath")
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // somehow os.copy does not properly preserve symlinks
    // os.copy(scriptSourcePath, workspacePath)
    os.call(("cp", "-R", scriptSourcePath, workspacePath))
    os.remove.all(workspacePath / "out")
  }

  /**
   * Helper method to modify a file containing text during your test suite.
   */
  def modifyFile(p: os.Path, f: String => String): Unit = os.write.over(p, f(os.read(p)))

  def teardownWorkspace() = {
    // try to stop the server
    try {
      os.proc(millReleaseFileOpt.get, "shutdown").call(
        cwd = workspacePath,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        env = millTestSuiteEnv
      )
    } catch {
      case NonFatal(e) =>
    }
  }
  override def utestAfterEach(path: Seq[String]): Unit = {
    if (clientServerMode) teardownWorkspace()
  }
}
