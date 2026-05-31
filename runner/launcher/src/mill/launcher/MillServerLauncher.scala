package mill.launcher

import mill.api.daemon.SystemStreams
import mill.client.{ClientUtil, LaunchedServer, ServerLauncher}
import mill.constants.BuildInfo
import mill.constants.EnvVars
import mill.client.lock.Locks
import mill.constants.Util
import mill.util.Jvm
import mill.rpc.RpcConsole

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class MillServerLauncher(
    streamsOpt: Option[SystemStreams],
    env: Map[String, String],
    args: Seq[String],
    forceFailureForTestingMillisDelay: Int,
    useFileLocks: Boolean,
    initServerFactory: (os.Path, Locks) => LaunchedServer,
    millVersion: String = BuildInfo.millVersion,
    jvmOpts: Seq[String],
    millRepositories: Seq[String]
) {
  private val serverInitWaitMillis = 10000

  def run(daemonDir: os.Path, javaHome: Option[os.Path], log: String => Unit): Int = {
    os.makeDir.all(daemonDir)
    // `Locks.forDirectory` opens a real file lock; pass it the on-disk path, not the alias.
    val locks = Locks.forDirectory(Jvm.realAbs(daemonDir), useFileLocks)
    log(s"launchOrConnectToServer: $locks")

    val config = ServerLauncher.DaemonConfig(
      millVersion = millVersion,
      javaVersion = javaHome.map(_.toString).getOrElse(""),
      jvmOpts = jvmOpts,
      millRepositories = millRepositories,
      pathRelativizerBase = env.getOrElse(EnvVars.OS_LIB_PATH_RELATIVIZER_BASE, "")
    )

    val launched = ServerLauncher.launchOrConnectToServer(
      locks,
      daemonDir,
      serverInitWaitMillis,
      () => initServerFactory(daemonDir, locks),
      serverDied => throw new mill.api.MillException("Server died during startup: " + serverDied),
      s => log(s),
      true,
      config = config
    )

    try {
      log(s"runWithConnection (RPC): $launched")
      val result = runRpc(launched.socket.get, javaHome, daemonDir, log)
      log(s"runWithConnection exit code: $result")
      result
    } finally {
      // `launched.close()` closes the socket; `locks.close()` frees the file
      // channels/handles eagerly opened by `Locks.forDirectory`, which the lock
      // `release()`s do not. Guard each so a failure in one still attempts the other.
      try launched.close()
      catch { case _: Exception => }
      try locks.close()
      catch { case _: Exception => }
    }
  }

  private def runRpc(
      socket: Socket,
      javaHome: Option[os.Path],
      daemonDir: os.Path,
      log: String => Unit
  ): Int = {
    val stdout = streamsOpt.map(_.out).getOrElse(System.out)
    val stderr = streamsOpt.map(_.err).getOrElse(System.err)
    val exitCode = AtomicInteger(-1)
    try {
      val socketIn = BufferedReader(InputStreamReader(socket.getInputStream))
      val socketOut = PrintStream(socket.getOutputStream, true)

      val init = DaemonRpc.Initialize(
        interactive = Util.hasConsole(),
        clientPid = ProcessHandle.current().pid(),
        clientMillVersion = BuildInfo.millVersion,
        clientJavaVersion = javaHome.map(_.toString).getOrElse(""),
        clientJvmOpts = jvmOpts,
        args = args,
        env = env,
        userSpecifiedProperties = ClientUtil.getUserSetProperties(),
        millRepositories = millRepositories
      )

      val stdoutPs = PrintStream(stdout, true)
      val stderrPs = PrintStream(stderr, true)

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
        stderr = stderrHandler,
        runSubprocess = DaemonRpc.defaultRunSubprocessWithStreams(streamsOpt)
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
        throw RuntimeException(s"Force failure for testing: $daemonDir")
      }

      val result = client(DaemonRpc.ClientToServer.RunCommand())
      exitCode.set(result.exitCode)
      client.close()
      exitCode.get()
    } catch {
      case e: RuntimeException if e.getMessage.startsWith("Force failure for testing:") =>
        throw e // Re-throw test exceptions
      case e: Exception =>
        e.printStackTrace(PrintStream(stderr))
        if (exitCode.get() < 0) exitCode.set(1)
        exitCode.get()
    }
  }
}
