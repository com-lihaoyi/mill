package mill.launcher

import mill.client.{ClientUtil, LaunchedServer, ServerLauncher}
import mill.constants.BuildInfo
import mill.client.lock.Locks
import mill.constants.Util
import mill.rpc.RpcConsole

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class MillServerLauncher(
    stdout: java.io.OutputStream,
    stderr: java.io.OutputStream,
    env: Map[String, String],
    args: Seq[String],
    forceFailureForTestingMillisDelay: Int,
    useFileLocks: Boolean,
    initServerFactory: (os.Path, Locks) => LaunchedServer,
    millVersion: String = BuildInfo.millVersion
) {
  private val serverInitWaitMillis = 10000

  def run(daemonDir: os.Path, javaHome: String, log: String => Unit): Int = {
    os.makeDir.all(daemonDir)
    val locks = Locks.forDirectory(daemonDir.toString, useFileLocks)
    log(s"launchOrConnectToServer: $locks")

    val launched = ServerLauncher.launchOrConnectToServer(
      locks,
      daemonDir,
      serverInitWaitMillis,
      () => initServerFactory(daemonDir, locks),
      serverDied => {
        System.err.println("Server died during startup:")
        System.err.println(serverDied.toString)
        System.exit(1)
      },
      s => log(s),
      true,
      millVersion = Some(millVersion)
    )

    try {
      log(s"runWithConnection (RPC): $launched")
      val result = runRpc(launched.socket.get, javaHome, daemonDir, log)
      log(s"runWithConnection exit code: $result")
      result
    } finally {
      try launched.close()
      catch { case _: Exception => }
    }
  }

  private def runRpc(
      socket: Socket,
      javaHome: String,
      daemonDir: os.Path,
      log: String => Unit
  ): Int = {
    val exitCode = new AtomicInteger(-1)
    try {
      val socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val socketOut = new PrintStream(socket.getOutputStream, true)

      val init = DaemonRpc.Initialize(
        interactive = Util.hasConsole(),
        clientMillVersion = BuildInfo.millVersion,
        clientJavaVersion = javaHome,
        args = args,
        env = env,
        userSpecifiedProperties = ClientUtil.getUserSetProperties()
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
        log(s"Force failure for testing in ${forceFailureForTestingMillisDelay}ms: $daemonDir")
        Thread.sleep(forceFailureForTestingMillisDelay)
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
