package mill.launcher

import mill.api.daemon.Logger
import mill.rpc.*
import upickle.ReadWriter

import java.io.{BufferedReader, PrintStream}

/**
 * Shared RPC message types for communication between Mill launcher and daemon.
 * These types are used by both DaemonRpcServer (in runner/daemon) and the
 * client code (in runner/launcher).
 */
object DaemonRpc {
  /**
   * Initialization data sent by the launcher when connecting.
   * Mirrors the old ClientInitData.
   */
  case class Initialize(
      interactive: Boolean,
      clientMillVersion: String,
      clientJavaVersion: String,
      args: Array[String],
      env: Map[String, String],
      userSpecifiedProperties: Map[String, String]
  ) derives ReadWriter

  /**
   * Messages from launcher (client) to daemon (server).
   */
  sealed trait ClientToServer extends MillRpcChannel.Message derives ReadWriter

  object ClientToServer {
    /**
     * Request to run a command.
     */
    case class RunCommand() extends ClientToServer {
      type Response = RunCommandResult
    }
  }

  /**
   * Result of running a command.
   */
  case class RunCommandResult(
      exitCode: Int,
      metadata: String = ""
  ) derives ReadWriter

  /**
   * Messages from daemon (server) to launcher (client).
   */
  sealed trait ServerToClient extends MillRpcChannel.Message derives ReadWriter

  object ServerToClient {
    /**
     * Request to run a subprocess on the launcher side with inherited stdin/stdout/stderr.
     * This allows interactive commands like repl/console/jshell to run on the launcher
     * where they have access to the terminal.
     *
     * @param cmd the command to run (executable and arguments)
     * @param env environment variables to set (in addition to propagated ones if propagateEnv is true)
     * @param cwd working directory for the subprocess
     * @param timeoutMillis timeout in milliseconds (-1 for no timeout)
     * @param mergeErrIntoOut whether to merge stderr into stdout
     * @param shutdownGracePeriodMillis grace period for shutdown (-1 for default)
     * @param propagateEnv whether to propagate the current environment variables
     * @param destroyOnExit whether to destroy the subprocess when the JVM exits
     */
    case class RunSubprocess(
        cmd: Seq[String],
        env: Map[String, String],
        cwd: String,
        timeoutMillis: Long,
        mergeErrIntoOut: Boolean,
        shutdownGracePeriodMillis: Long,
        propagateEnv: Boolean,
        destroyOnExit: Boolean
    ) extends ServerToClient {
      type Response = SubprocessResult
    }
  }

  /**
   * Result of running a subprocess.
   */
  case class SubprocessResult(exitCode: Int) derives ReadWriter

  /**
   * Create an RPC client for communicating with the Mill daemon.
   *
   * @param initialize the initialization data to send to the daemon
   * @param serverToClient input stream from the daemon
   * @param clientToServer output stream to the daemon
   * @param stdout handler for stdout messages from the daemon
   * @param stderr handler for stderr messages from the daemon
   * @param runSubprocess handler for running subprocesses on the launcher side
   * @return an RPC client that can be used to send commands to the daemon
   */
  def createClient(
      initialize: Initialize,
      serverToClient: BufferedReader,
      clientToServer: PrintStream,
      stdout: RpcConsole.Message => Unit = RpcConsole.stdoutHandler,
      stderr: RpcConsole.Message => Unit = RpcConsole.stderrHandler,
      runSubprocess: ServerToClient.RunSubprocess => SubprocessResult = defaultRunSubprocess
  ): MillRpcClient[ClientToServer, ServerToClient] = {
    mill.constants.DebugLog.println(s"DaemonRpc.createClient: creating transport")

    val transport = MillRpcWireTransport(
      name = "DaemonRpcClient",
      serverToClient = serverToClient,
      clientToServer = clientToServer,
      writeSynchronizer = new Object
    )

    mill.constants.DebugLog.println(s"DaemonRpc.createClient: creating client with init=$initialize")

    val log = new Logger.Actions {
      override def error(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:error] $msg")
      override def warn(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:warn] $msg")
      override def info(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:info] $msg")
      override def debug(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:debug] $msg")
      override def ticker(msg: String): Unit = mill.constants.DebugLog.println(s"[DaemonRpc:ticker] $msg")
    }

    // Handler for server-to-client messages
    val serverMessageHandler = new MillRpcChannel[ServerToClient] {
      override def apply(input: ServerToClient): input.Response = {
        mill.constants.DebugLog.println(s"DaemonRpc: received server message: $input")
        input match {
          case req: ServerToClient.RunSubprocess =>
            runSubprocess(req).asInstanceOf[input.Response]
        }
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

  /**
   * Default implementation for running subprocesses on the launcher.
   * Uses os.Inherit for stdin/stdout/stderr.
   */
  def defaultRunSubprocess(req: ServerToClient.RunSubprocess): SubprocessResult = {
    import req.*
    mill.constants.DebugLog.println(s"DaemonRpc.defaultRunSubprocess: running ${cmd.mkString(" ")}")

    try {
      val builder = new ProcessBuilder(cmd: _*)
        .directory(new java.io.File(cwd))
        .inheritIO()

      // Set up environment
      val procEnv = builder.environment()
      if (!propagateEnv) {
        procEnv.clear()
      }
      env.foreach { case (k, v) => procEnv.put(k, v) }

      if (mergeErrIntoOut) {
        builder.redirectErrorStream(true)
      }

      val process = builder.start()

      // Handle destroyOnExit
      if (destroyOnExit) {
        Runtime.getRuntime.addShutdownHook(new Thread(() => {
          if (process.isAlive) {
            process.destroy()
          }
        }))
      }

      // Wait for the process with optional timeout
      val exitCode = if (timeoutMillis > 0) {
        val finished = process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
          // Handle graceful shutdown
          if (shutdownGracePeriodMillis > 0) {
            process.destroy()
            val gracefulExit = process.waitFor(shutdownGracePeriodMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!gracefulExit) {
              process.destroyForcibly()
            }
          } else {
            process.destroyForcibly()
          }
          process.waitFor()
        } else {
          process.exitValue()
        }
      } else {
        process.waitFor()
      }

      mill.constants.DebugLog.println(s"DaemonRpc.defaultRunSubprocess: exitCode=$exitCode")
      SubprocessResult(exitCode)
    } catch {
      case e: Exception =>
        mill.constants.DebugLog.println(s"DaemonRpc.defaultRunSubprocess: exception: $e")
        SubprocessResult(1)
    }
  }
}
