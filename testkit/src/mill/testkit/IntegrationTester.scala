package mill.testkit

import mill.main.client.EnvVars.MILL_TEST_SUITE
import mill.define.Segments
import mill.eval.Evaluator
import mill.main.client.OutFiles
import mill.define.SelectMode
import ujson.Value

/**
 * Helper meant for executing Mill integration tests, which runs Mill in a subprocess
 * against a folder with a `build.mill` and project files. Provides APIs such as [[eval]]
 * to run Mill commands and [[out]] to inspect the results on disk. You can use
 * [[modifyFile]] or any of the OS-Lib `os.*` APIs on the [[workspacePath]] to modify
 * project files in the course of the test.
 *
 * @param clientServerMode Whether to run Mill in client-server mode. If `false`, Mill
 *                         is run with `--no-server`
 * @param workspaceSourcePath The folder in which the `build.mill` and project files being
 *                            tested comes from. These are copied into a temporary folder
 *                            and are not modified during tests
 * @param millExecutable What Mill executable to use.
 */
class IntegrationTester(
    val clientServerMode: Boolean,
    val workspaceSourcePath: os.Path,
    val millExecutable: os.Path,
    override val debugLog: Boolean = false,
    val baseWorkspacePath: os.Path = os.pwd
) extends IntegrationTester.Impl {
  initWorkspace()
}

object IntegrationTester {
  def millTestSuiteEnv: Map[String, String] = Map(
    MILL_TEST_SUITE -> this.getClass().toString(),
    "JAVA_HOME" -> sys.props("java.home")
  )

  /**
   * A very simplified version of `os.CommandResult` meant for easily
   * performing assertions against.
   */
  case class EvalResult(isSuccess: Boolean, out: String, err: String) {
    def debugString: String = {
      s"""Success: $isSuccess
         |
         |stdout:
         |$out
         |
         |stderr:
         |$err
         |""".stripMargin
    }
  }

  trait Impl extends AutoCloseable with IntegrationTesterBase {

    def millExecutable: os.Path
    def workspaceSourcePath: os.Path

    val clientServerMode: Boolean

    def debugLog = false

    /**
     * Evaluates a Mill command. Essentially the same as `os.call`, except it
     * provides the Mill executable and some test flags and environment variables
     * for you, and wraps the output in a [[IntegrationTester.EvalResult]] for
     * convenience.
     */
    def eval(
        cmd: os.Shellable,
        env: Map[String, String] = Map.empty,
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

      val shellable: os.Shellable =
        (millExecutable, serverArgs, "--ticker", "false", debugArgs, cmd)

      val res0 = os.call(
        cmd = shellable,
        env = millTestSuiteEnv ++ env,
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

    /**
     * Helpers to read the `.json` metadata files belonging to a particular task
     * (specified by [[selector0]]) from the `out/` folder.
     */
    def out(selector0: String): Meta = new Meta(selector0)

    class Meta(selector0: String) {

      /**
       * Returns the raw text of the `.json` metadata file
       */
      def text: String = {
        val Seq((Seq(selector), _)) =
          mill.resolve.ParseArgs.apply(Seq(selector0), SelectMode.Separated).getOrElse(???)

        val segments = selector._2.getOrElse(Segments()).value.flatMap(_.pathSegments)
        os.read(workspacePath / OutFiles.out / segments.init / s"${segments.last}.json")
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
        os.call(
          cmd = (millExecutable, "--no-build-lock", "shutdown"),
          cwd = workspacePath,
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit,
          env = millTestSuiteEnv,
          check = false
        )
      }

      removeServerIdFile()
    }
  }

}
