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
    Files.createDirectories(daemonDir)
    val locks = Locks.forDirectory(daemonDir.toString, useFileLocks)
    log.accept(s"launchOrConnectToServer: $locks")

    val launched = ServerLauncher.launchOrConnectToServer(
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
      true
    )

    try {
      log.accept(s"runWithConnection (RPC): $launched")
      val result = runRpc(launched.socket, javaHome, daemonDir, log.accept)
      log.accept(s"runWithConnection exit code: $result")
      result
    } finally {
      try launched.close()
      catch { case _: Exception => }
    }
  }

  def runRpc(socket: Socket, javaHome: String, daemonDir: Path, log: String => Unit): Int = {
    val exitCode = new AtomicInteger(-1)
    try {
      val socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val socketOut = new PrintStream(socket.getOutputStream, true)

      val init = DaemonRpc.Initialize(
        interactive = Util.hasConsole(),
        clientMillVersion = BuildInfo.millVersion,
        clientJavaVersion = javaHome,
        args = args,
        env = env.asScala.toMap,
        userSpecifiedProperties = ClientUtil.getUserSetProperties().asScala.toMap
      )

      val stdoutPs = new PrintStream(stdout, true)
      val stderrPs = new PrintStream(stderr, true)

      val stdoutHandler: RpcConsole.Message => Unit = {
        case RpcConsole.Message.Print(s) => stdoutPs.print(s)
        case RpcConsole.Message.Flush => stdoutPs.flush()
      }

      val stderrHandler: RpcConsole.Message => Unit = {
        case RpcConsole.Message.Print(s) => stderrPs.print(s)
        case RpcConsole.Message.Flush => stderrPs.flush()
      }

      val client = DaemonRpc.createClient(
        initialize = init,
        serverToClient = socketIn,
        clientToServer = socketOut,
        stdout = stdoutHandler,
        stderr = stderrHandler
      )

      // For testing: run command in background while main thread throws after delay
      if (forceFailureForTestingMillisDelay > 0) {
        val commandThread = new Thread(() => {
          try {
            val result = client(DaemonRpc.ClientToServer.RunCommand())
            exitCode.set(result.exitCode)
          } catch { case _: Exception => }
        })
        commandThread.start()
        log.accept(
          s"Force failure for testing in ${forceFailureForTestingMillisDelay}ms: $daemonDir"
        )
        Thread.sleep(forceFailureForTestingMillisDelay)
        // Let the test exception propagate (not caught below)
        throw new RuntimeException(s"Force failure for testing: $daemonDir")
      }

      val result = client(DaemonRpc.ClientToServer.RunCommand())
      exitCode.set(result.exitCode)
      client.close()
      exitCode.get()
    } catch {
      case e: RuntimeException if e.getMessage.startsWith("Force failure for testing:") =>
        throw e // Re-throw test exceptions
      case e: Exception =>
        e.printStackTrace(new PrintStream(stderr))
        if (exitCode.get() < 0) exitCode.set(1)
        exitCode.get()
    }
  }
}

object MillRpcServerLauncher {
  @FunctionalInterface
  trait InitServerFactory {
    @throws[Exception]
    def create(daemonDir: Path, locks: Locks): LaunchedServer
  }
}
