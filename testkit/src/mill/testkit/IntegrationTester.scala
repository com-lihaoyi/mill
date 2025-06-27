package mill.testkit

import mill.constants.OutFiles
import mill.define.Segments
import mill.define.Cached
import mill.define.SelectMode
import ujson.Value

import scala.concurrent.duration.*

/**
 * Helper meant for executing Mill integration tests, which runs Mill in a subprocess
 * against a folder with a `build.mill` and project files. Provides APIs such as [[eval]]
 * to run Mill commands and [[out]] to inspect the results on disk. You can use
 * [[modifyFile]] or any of the OS-Lib `os.*` APIs on the [[workspacePath]] to modify
 * project files in the course of the test.
 *
 * @param daemonMode Whether to run Mill in client-server mode. If `false`, Mill
 *                         is run with `--no-server`
 * @param workspaceSourcePath The folder in which the `build.mill` and project files being
 *                            tested comes from. These are copied into a temporary folder
 *                            and are not modified during tests
 * @param millExecutable What Mill executable to use.
 */
class IntegrationTester(
    val daemonMode: Boolean,
    val workspaceSourcePath: os.Path,
    val millExecutable: os.Path,
    override val debugLog: Boolean = false,
    val baseWorkspacePath: os.Path = os.pwd,
    val propagateJavaHome: Boolean = true
) extends IntegrationTester.Impl {
  initWorkspace()
}

object IntegrationTester {

  /**
   * A very simplified version of `os.CommandResult` meant for easily
   * performing assertions against.
   */
  case class EvalResult(exitCode: Int, out: String, err: String) {
    def isSuccess = exitCode == 0
  }

  /** An [[Impl.eval]] that is prepared for execution but haven't been executed yet. Run it with [[run]]. */
  case class PreparedEval(
      cmd: os.Shellable,
      env: Map[String, String],
      cwd: os.Path,
      timeout: Duration,
      check: Boolean,
      propagateEnv: Boolean = true,
      shutdownGracePeriod: Long = 100,
      run: () => EvalResult
  ) {

    /** Clues to use for with [[withTestClues]]. */
    def clues: Seq[utest.TestValue] = Seq(
      // Copy-pastable shell command that you can run in bash/zsh/whatever
      asTestValue("cmd", cmd.value.iterator.map(pprint.Util.literalize(_)).mkString(" ")),
      asTestValue("cmd.shellable", cmd),
      asTestValue(env),
      asTestValue(cwd),
      asTestValue(timeout),
      asTestValue(check),
      asTestValue(propagateEnv),
      asTestValue(shutdownGracePeriod)
    ).map(tv => tv.copy(name = "eval." + tv.name))
  }

  trait Impl extends AutoCloseable with IntegrationTesterBase {

    def millExecutable: os.Path
    def workspaceSourcePath: os.Path

    val daemonMode: Boolean

    def debugLog = false

    /**
     * Prepares to evaluate a Mill command. Run it with [[IntegrationTester.PreparedEval.run]].
     *
     * Useful when you need the [[IntegrationTester.PreparedEval.clues]].
     */
    def prepEval(
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
    ): IntegrationTester.PreparedEval = {
      val serverArgs = Option.when(!daemonMode)("--no-daemon")

      val debugArgs = Option.when(debugLog)("--debug")

      val shellable: os.Shellable =
        (millExecutable, serverArgs, "--ticker", "false", debugArgs, cmd)

      val callEnv = millTestSuiteEnv ++ env

      def run() = {
        val res0 = os.call(
          cmd = shellable,
          env = callEnv,
          cwd = cwd,
          stdin = stdin,
          stdout = stdout,
          stderr = stderr,
          mergeErrIntoOut = mergeErrIntoOut,
          timeout = timeout,
          check = check,
          propagateEnv = propagateEnv,
          shutdownGracePeriod = timeoutGracePeriod
        )

        IntegrationTester.EvalResult(
          res0.exitCode,
          fansi.Str(res0.out.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim,
          fansi.Str(res0.err.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim
        )
      }

      PreparedEval(
        cmd = shellable,
        env = callEnv,
        cwd = cwd,
        timeout = if (timeout == -1) Duration.Inf else timeout.millis,
        check = check,
        propagateEnv = propagateEnv,
        shutdownGracePeriod = timeoutGracePeriod,
        run = run
      )
    }

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
      prepEval(
        cmd = cmd,
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
      ).run()
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
          mill.resolve.ParseArgs.apply(Seq(selector0), SelectMode.Separated).get

        val segments = selector._2.getOrElse(Segments()).value.flatMap(_.pathSegments)
        os.read(workspacePath / OutFiles.out / segments.init / s"${segments.last}.json")
      }

      /**
       * Returns the `.json` metadata file contents parsed into a [[Evaluator.Cached]]
       * object, containing both the value as JSON and the associated metadata (e.g. hashes)
       */
      def cached: Cached = upickle.default.read[Cached](text)

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
    override def close(): Unit = removeProcessIdFile()
  }

}
