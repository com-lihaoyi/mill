package mill.testkit

import mill.eval.Evaluator
import mill.resolve.SelectMode
import ujson.Value

import scala.util.control.NonFatal

class IntegrationTester(val clientServerMode: Boolean,
                        val workspaceSourcePath: os.Path,
                        val millExecutable: os.Path) extends IntegrationTester.Impl{
  initWorkspace()
}
object IntegrationTester {
  /**
   * A very simplified version of `os.CommandResult` meant for easily
   * performing assertions against.
   */
  case class EvalResult(isSuccess: Boolean, out: String, err: String)

  trait Impl extends AutoCloseable {

    def millExecutable: os.Path
    protected def workspaceSourcePath: os.Path

    val clientServerMode: Boolean

    /**
     * The working directory of the integration test suite, which is the root of the
     * Mill build being tested. Contains the `build.sc` file, any application code, and
     * the `out/` folder containing the build output
     */
    val workspacePath: os.Path = os.temp.dir(deleteOnExit = false)



    def debugLog = false

    /**
     * Evaluates a Mill [[cmd]]. Essentially the same as `os.call`, except it
     * provides the Mill executable and some test flags and environment variables
     * for you, and wraps the output in a [[IntegrationTester.EvalResult]] for
     * convenience.
     */
    def eval(
              cmd: os.Shellable,
              env: Map[String, String] = millTestSuiteEnv,
              cwd: os.Path = workspacePath,
              stdin: os.ProcessInput = os.Pipe,
              stdout: os.ProcessOutput = os.Pipe,
              stderr: os.ProcessOutput = os.Pipe,
              mergeErrIntoOut: Boolean = false,
              timeout: Long = -1,
              check: Boolean = false,
              propagateEnv: Boolean = true,
              timeoutGracePeriod: Long = 100
            ): IntegrationTester.EvalResult = {
      val serverArgs = Option.when(!clientServerMode)("--no-server")

      val debugArgs = Option.when(debugLog)("--debug")

      val shellable: os.Shellable = (millExecutable, serverArgs, debugArgs, cmd)
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
        timeoutGracePeriod = timeoutGracePeriod
      )

      IntegrationTester.EvalResult(
        res0.exitCode == 0,
        fansi.Str(res0.out.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim,
        fansi.Str(res0.err.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim
      )
    }

    private val millTestSuiteEnv: Map[String, String] =
      Map("MILL_TEST_SUITE" -> this.getClass().toString())

    /**
     * Helpers to read the `.json` metadata files belonging to a particular task
     * (specified by [[selector0]]) from the `out/` folder.
     */
    def meta(selector0: String): Meta = new Meta(selector0)

    class Meta(selector0: String) {
      /**
       * Returns the raw text of the `.json` metadata file
       */
      def text: String = {
        val Seq((List(selector), _)) =
          mill.resolve.ParseArgs.apply(Seq(selector0), SelectMode.Separated).getOrElse(???)

        val segments = selector._2.value.flatMap(_.pathSegments)
        os.read(workspacePath / "out" / segments.init / s"${segments.last}.json")
      }

      /**
       * Returns the `.json` metadata file contents parsed into a [[Evaluator.Cached]]
       * object, containing both the value as JSON and the associated metadata (e.g. hashes)
       */
      def cached: Evaluator.Cached = upickle.default.read[Evaluator.Cached](text)

      /**
       * Returns the value as JSON
       */
      def json: Value.Value = ujson.read(cached.value)

      /**
       * Returns the value parsed from JSON into a value of type [[T]]
       */
      def value[T: upickle.default.Reader]: T = upickle.default.read[T](cached.value)
    }

    /**
     * Initializes the workspace in preparation for integration testing
     */
    def initWorkspace(): Unit = {
      println(s"Copying integration test sources from $workspaceSourcePath to $workspacePath")
      os.remove.all(workspacePath)
      os.makeDir.all(workspacePath / os.up)
      // somehow os.copy does not properly preserve symlinks
      // os.copy(scriptSourcePath, workspacePath)
      os.call(("cp", "-R", workspaceSourcePath, workspacePath))
      os.remove.all(workspacePath / "out")
    }

    /**
     * Helper method to modify a file containing text during your test suite.
     */
    def modifyFile(p: os.Path, f: String => String): Unit = os.write.over(p, f(os.read(p)))

    /**
     * Tears down the workspace at the end of a test run, shutting down any
     * in-process Mill background servers
     */
    override def close(): Unit = {
      if (clientServerMode) {
        // try to stop the server
        try {
          os.call(
            cmd = (millExecutable, "shutdown"),
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
    }
  }

}

