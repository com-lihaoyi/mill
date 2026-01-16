package mill.launcher

import mill.client.{BuildInfo, ClientUtil, LaunchedServer, ServerLauncher}
import mill.client.lock.Locks
import mill.constants.Util
import mill.rpc.RpcConsole

import java.io.{BufferedReader, InputStream, InputStreamReader, OutputStream, PrintStream}
import java.net.Socket
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/**
 * RPC-based Mill server launcher. Uses the RPC protocol instead of ProxyStream.
 */
class MillRpcServerLauncher(
    stdin: InputStream,
    stdout: OutputStream,
    stderr: OutputStream,
    env: java.util.Map[String, String],
    args: Array[String],
    forceFailureForTestingMillisDelay: Int,
    useFileLocks: Boolean,
    initServerFactory: MillRpcServerLauncher.InitServerFactory
) {
  private val serverInitWaitMillis = 10000

  def run(daemonDir: Path, javaHome: String, log: Consumer[String]): Int = {
    mill.constants.DebugLog.println(s"MillRpcServerLauncher.run: starting")
    Files.createDirectories(daemonDir)

    val locks = Locks.forDirectory(daemonDir.toString, useFileLocks)

    log.accept(s"launchOrConnectToServer: $locks")

    val launched = try {
      ServerLauncher.launchOrConnectToServer(
        locks,
        daemonDir,
        serverInitWaitMillis,
        () => initServerFactory.create(daemonDir, locks),
        serverDied => {
          System.err.println("Server died during startup:")
          System.err.println(serverDied.toString)
          System.exit(1)
        },
        log,
        true /* openSocket */
      )
    } catch {
      case e: Exception =>
        mill.constants.DebugLog.println(s"MillRpcServerLauncher.run: exception during launch: $e")
        throw e
    }

    try {
      log.accept(s"runWithConnection (RPC): $launched")

      forceTestFailure(daemonDir, log)

      val result = runRpc(launched.socket, javaHome, log)
      log.accept(s"runWithConnection exit code: $result")
      result
    } finally {
      try launched.close()
      catch { case _: Exception => }
    }
  }

  private def runRpc(socket: Socket, javaHome: String, log: Consumer[String]): Int = {
    mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: starting")

    val exitCode = new AtomicInteger(-1)

    try {
      val socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val socketOut = new PrintStream(socket.getOutputStream, true)

      mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: creating client")

      val init = DaemonRpc.Initialize(
        interactive = Util.hasConsole(),
        clientMillVersion = BuildInfo.millVersion,
        clientJavaVersion = javaHome,
        args = args,
        env = env.asScala.toMap,
        userSpecifiedProperties = ClientUtil.getUserSetProperties().asScala.toMap
      )

      mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: init=$init")

      // Create stdout/stderr handlers that write to the provided streams
      val stdoutPs = new PrintStream(stdout, true)
      val stderrPs = new PrintStream(stderr, true)

      val stdoutHandler: RpcConsole.Message => Unit = {
        case RpcConsole.Message.Print(s) =>
          stdoutPs.print(s)
        case RpcConsole.Message.Flush =>
          stdoutPs.flush()
      }

      val stderrHandler: RpcConsole.Message => Unit = {
        case RpcConsole.Message.Print(s) =>
          stderrPs.print(s)
        case RpcConsole.Message.Flush =>
          stderrPs.flush()
      }

      val client = DaemonRpc.createClient(
        initialize = init,
        serverToClient = socketIn,
        clientToServer = socketOut,
        stdout = stdoutHandler,
        stderr = stderrHandler
      )

      mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: client created, sending RunCommand")

      // Send the RunCommand request
      val result = client(DaemonRpc.ClientToServer.RunCommand())

      mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: result=$result")
      exitCode.set(result.exitCode)

      client.close()

    } catch {
      case e: Exception =>
        mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: exception: $e")
        e.printStackTrace(new PrintStream(stderr))
        if (exitCode.get() < 0) exitCode.set(1)
    }

    mill.constants.DebugLog.println(s"MillRpcServerLauncher.runRpc: returning exitCode=${exitCode.get()}")
    exitCode.get()
  }

  private def forceTestFailure(daemonDir: Path, log: Consumer[String]): Unit = {
    if (forceFailureForTestingMillisDelay > 0) {
      log.accept(s"Force failure for testing in ${forceFailureForTestingMillisDelay}ms: $daemonDir")
      Thread.sleep(forceFailureForTestingMillisDelay)
      throw new RuntimeException(s"Force failure for testing: $daemonDir")
    } else {
      log.accept(s"No force failure for testing: $daemonDir")
    }
  }
}

object MillRpcServerLauncher {
  /**
   * Factory interface for creating a server. This is a SAM trait that can throw exceptions,
   * making it compatible with Java lambdas that throw checked exceptions.
   */
  @FunctionalInterface
  trait InitServerFactory {
    @throws[Exception]
    def create(daemonDir: Path, locks: Locks): LaunchedServer
  }
}
