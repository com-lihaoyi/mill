package mill.launcher

import mill.api.daemon.{LauncherSubprocess, Logger}
import mill.rpc.*
import upickle.ReadWriter

import java.io.{BufferedReader, OutputStream, PrintStream}

/**
 * RPC message types for launcher-daemon communication.
 */
object DaemonRpc {
  // ReadWriter for LauncherSubprocess.Config (defined in core/api/daemon which has no upickle dependency)
  given ReadWriter[LauncherSubprocess.Config] = upickle.default.macroRW
  case class Initialize(
      interactive: Boolean,
      clientMillVersion: String,
      clientJavaVersion: String,
      clientJvmOpts: Seq[String],
      args: Seq[String],
      env: Map[String, String],
      userSpecifiedProperties: Map[String, String],
      millRepositories: Seq[String]
  ) derives ReadWriter

  sealed trait ClientToServer extends MillRpcChannel.Message derives ReadWriter
  object ClientToServer {
    case class RunCommand() extends ClientToServer { type Response = RunCommandResult }
  }

  case class RunCommandResult(exitCode: Int, metadata: String = "") derives ReadWriter

  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter
  object ServerToClient {

    /** Request to run a subprocess on the launcher with inherited I/O. */
    case class RunSubprocess(config: mill.api.daemon.LauncherSubprocess.Config)
        extends ServerToClient {
      type Response = SubprocessResult
    }

    /** Request to poll for available stdin data from the client terminal. */
    case class PollStdin() extends ServerToClient {
      type Response = StdinResult
    }

    /** Request to get current terminal dimensions from the client. */
    case class GetTerminalDims() extends ServerToClient {
      type Response = TerminalDimsResult
    }
  }

  case class SubprocessResult(exitCode: Int) derives ReadWriter

  /** Result of polling for stdin data. Contains available bytes (empty if none). */
  case class StdinResult(bytes: Array[Byte]) derives ReadWriter

  /** Result of getting terminal dimensions. None means dimension is unknown. */
  case class TerminalDimsResult(width: Option[Int], height: Option[Int]) derives ReadWriter

  def createClient(
      initialize: Initialize,
      serverToClient: BufferedReader,
      clientToServer: PrintStream,
      stdout: RpcConsole.Message => Unit = RpcConsole.stdoutHandler,
      stderr: RpcConsole.Message => Unit = RpcConsole.stderrHandler,
      runSubprocess: ServerToClient.RunSubprocess => SubprocessResult = defaultRunSubprocess,
      pollStdin: ServerToClient.PollStdin => StdinResult = defaultPollStdin,
      getTerminalDims: ServerToClient.GetTerminalDims => TerminalDimsResult = defaultGetTerminalDims
  ): MillRpcClient[ClientToServer, ServerToClient] = {
    val transport = MillRpcWireTransport(
      name = "DaemonRpcClient",
      serverToClient = serverToClient,
      clientToServer = clientToServer,
      writeSynchronizer = new Object
    )

    val log = Logger.Actions.noOp

    val serverMessageHandler = new MillRpcChannel[ServerToClient] {
      override def apply(input: ServerToClient): input.Response = input match {
        case req: ServerToClient.RunSubprocess =>
          runSubprocess(req).asInstanceOf[input.Response]
        case req: ServerToClient.PollStdin =>
          pollStdin(req).asInstanceOf[input.Response]
        case req: ServerToClient.GetTerminalDims =>
          getTerminalDims(req).asInstanceOf[input.Response]
      }
    }

    MillRpcClient.create[Initialize, ClientToServer, ServerToClient](
      initialize = initialize,
      wireTransport = transport,
      log = log,
      stdout = stdout,
      stderr = stderr
    )(serverMessageHandler)
  }

  private def outputToStream(dest: OutputStream): os.ProcessOutput =
    os.ProcessOutput.ReadBytes { (arr, n) =>
      dest.write(arr, 0, n)
      dest.flush()
    }

  def defaultRunSubprocessWithStreams(
      stdout: OutputStream,
      stderr: OutputStream
  ): ServerToClient.RunSubprocess => SubprocessResult = { req =>
    try {
      val result = os.proc(req.config.cmd).call(
        cwd = os.Path(req.config.cwd),
        env = req.config.env,
        stdin = os.Inherit,
        stdout = outputToStream(stdout),
        stderr = outputToStream(stderr),
        mergeErrIntoOut = req.config.mergeErrIntoOut,
        timeout = req.config.timeoutMillis,
        propagateEnv = req.config.propagateEnv,
        check = false
      )
      SubprocessResult(result.exitCode)
    } catch {
      case _: Exception => SubprocessResult(1)
    }
  }

  def defaultRunSubprocess(req: ServerToClient.RunSubprocess): SubprocessResult = {
    try {
      val result = os.proc(req.config.cmd).call(
        cwd = os.Path(req.config.cwd),
        env = req.config.env,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        mergeErrIntoOut = req.config.mergeErrIntoOut,
        timeout = req.config.timeoutMillis,
        propagateEnv = req.config.propagateEnv,
        check = false
      )
      SubprocessResult(result.exitCode)
    } catch {
      case _: Exception => SubprocessResult(1)
    }
  }

  def defaultPollStdin(@scala.annotation.unused req: ServerToClient.PollStdin): StdinResult = {
    try {
      val available = System.in.available()
      if (available > 0) {
        val toRead = math.min(available, 4 * 1024)
        val buffer = new Array[Byte](toRead)
        val bytesRead = System.in.read(buffer)
        if (bytesRead == toRead) StdinResult(buffer)
        else if (bytesRead > 0) StdinResult(java.util.Arrays.copyOf(buffer, bytesRead))
        else StdinResult(Array.empty)
      } else {
        StdinResult(Array.empty)
      }
    } catch {
      case _: Exception => StdinResult(Array.empty)
    }
  }

  def defaultGetTerminalDims(
      @scala.annotation.unused req: ServerToClient.GetTerminalDims
  ): TerminalDimsResult = {
    MillProcessLauncher.getTerminalDims()
  }
}
