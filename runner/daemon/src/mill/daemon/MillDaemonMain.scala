package mill.daemon

import mill.api.SystemStreams
import mill.client.ClientUtil
import mill.client.lock.{DoubleLock, Lock, Locks}
import mill.constants.OutFiles
import sun.misc.{Signal, SignalHandler}
import mill.api.BuildCtx
import scala.util.{Properties, Try}

object MillDaemonMain {
  def main(args0: Array[String]): Unit = {

    // Set by an integration test
    if (System.getenv("MILL_DAEMON_CRASH") == "true")
      sys.error("Mill daemon early crash requested")

    if (Properties.isWin)
      // temporarily disabling FFM use by coursier, which has issues with the way
      // Mill manages class loaders, throwing things like
      // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
      sys.props("coursier.windows.disable-ffm") = "true"

    mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
      // Disable SIGINT interrupt signal in the Mill server.
      //
      // This gets passed through from the client to server whenever the user
      // hits `Ctrl-C`, which by default kills the server, which defeats the purpose
      // of running a background daemon. Furthermore, the background daemon already
      // can detect when the Mill client goes away, which is necessary to handle
      // the case when a Mill client that did *not* spawn the server gets `CTRL-C`ed
      Signal.handle(
        new Signal("INT"),
        new SignalHandler() {
          def handle(sig: Signal) = {} // do nothing
        }
      )

      val acceptTimeoutMillis =
        Try(System.getProperty("mill.server_timeout").toInt).getOrElse(30 * 1000) // 30 minutes

      new MillDaemonMain(
        daemonDir = os.Path(args0(0)),
        acceptTimeoutMillis = acceptTimeoutMillis,
        Locks.files(args0(0))
      ).run()

      System.exit(ClientUtil.ExitServerCodeWhenIdle())
    }
  }
}
class MillDaemonMain(
    daemonDir: os.Path,
    acceptTimeoutMillis: Int,
    locks: Locks
) extends mill.server.Server[RunnerState](
      daemonDir,
      acceptTimeoutMillis,
      locks
    ) {

  def stateCache0 = RunnerState.empty

  val out = os.Path(OutFiles.out, BuildCtx.workspaceRoot)

  val outLock = new DoubleLock(
    MillMain0.outMemoryLock,
    Lock.file((out / OutFiles.millOutLock).toString)
  )

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing
  ): (Boolean, RunnerState) = {
    try MillMain0.main0(
        args = args,
        stateCache = stateCache,
        mainInteractive = mainInteractive,
        streams0 = streams,
        env = env,
        setIdle = setIdle,
        userSpecifiedProperties0 = userSpecifiedProperties,
        initialSystemProperties = initialSystemProperties,
        systemExit = systemExit,
        daemonDir = daemonDir,
        outLock = outLock
      )
    catch MillMain0.handleMillException(streams.err, stateCache)
  }
}
