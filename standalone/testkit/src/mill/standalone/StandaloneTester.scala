package mill.standalone

import mill.constants.DaemonFiles.processId
import mill.constants.OutFiles.{millDaemon, millNoDaemon, out}
import os.{Path, ProcessInput, ProcessOutput, Shellable}

class StandaloneTester(
    val millExecutable: os.Path,
    val daemonMode: Boolean,
    val debugLog: Boolean = false,
    val workspacePath: os.Path = os.pwd,
    val propagateJavaHome: Boolean = true
) extends StandaloneTester.Impl

object StandaloneTester {

  case class EvalResult(exitCode: Int, out: String, err: String) {
    def isSuccess: Boolean = exitCode == 0

    def debugString: String = {
      s"""Success: $isSuccess (exit code: $exitCode)
         |
         |stdout:
         |$out
         |
         |stderr:
         |$err
         |""".stripMargin
    }
  }

  trait Impl extends AutoCloseable {

    def workspacePath: os.Path

    def millExecutable: os.Path

    def daemonMode: Boolean

    def debugLog: Boolean

    def propagateJavaHome: Boolean

    def millTestSuiteEnv: Map[String, String] = Option.when(propagateJavaHome)(
      "JAVA_HOME" -> sys.props("java.home")
    ).toMap

    def eval(
        cmd: Shellable,
        env: Map[String, String] = Map(),
        cwd: Path = workspacePath,
        stdin: ProcessInput = os.Pipe,
        stdout: ProcessOutput = os.Pipe,
        stderr: ProcessOutput = os.Pipe,
        mergeErrIntoOut: Boolean = false,
        timeout: Long = -1,
        check: Boolean = false,
        shutdownGracePeriod: Long = 100
    ) = {
      val serverArgs = Option.when(!daemonMode)("--no-daemon")
      val debugArgs = Option.when(debugLog)("--debug")
      val cmd0: os.Shellable = (millExecutable, serverArgs, debugArgs, cmd)

      val callEnv = millTestSuiteEnv ++ env

      val res0 = os.call(
        cmd0,
        env = callEnv,
        cwd = cwd,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        mergeErrIntoOut = mergeErrIntoOut,
        timeout = timeout,
        check = check,
        shutdownGracePeriod = shutdownGracePeriod
      )

      EvalResult(
        res0.exitCode,
        fansi.Str(res0.out.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim,
        fansi.Str(res0.err.text(), errorMode = fansi.ErrorMode.Strip).plainText.trim
      )
    }

    def close() = removeProcessIdFile()

    def removeProcessIdFile() = {
      val outDir = os.Path(out, workspacePath)
      if (os.exists(outDir)) {
        if (daemonMode) {
          val serverPath = outDir / millDaemon
          os.remove(serverPath / processId)
        } else {
          val serverPath0 = outDir / millNoDaemon

          for (serverPath <- os.list.stream(serverPath0)) os.remove(serverPath / processId)

        }
        Thread.sleep(500) // give a moment for the server to notice the file is gone and exit
      }
    }
  }
}
