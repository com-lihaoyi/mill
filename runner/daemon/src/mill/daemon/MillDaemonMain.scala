package mill.daemon

import mill.api.{BuildCtx, SystemStreams}
import mill.client.ClientUtil
import mill.client.lock.{Lock, Locks}
import mill.constants.OutFiles
import mill.server.Server

import scala.concurrent.duration.*
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
      Server.overrideSigIntHandling()

      val acceptTimeout =
        Try(System.getProperty("mill.server_timeout").toInt.millis).getOrElse(30.minutes)

      new MillDaemonMain(
        daemonDir = os.Path(args0(0)),
        acceptTimeout = acceptTimeout,
        Locks.files(args0(0))
      ).run()

      System.exit(ClientUtil.ExitServerCodeWhenIdle())
    }
  }
}
class MillDaemonMain(
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks
) extends mill.server.MillDaemonServer[RunnerState](
      daemonDir,
      acceptTimeout,
      locks
    ) {

  def stateCache0 = RunnerState.empty

  val out: os.Path = os.Path(OutFiles.out, BuildCtx.workspaceRoot)

  val outLock = MillMain0.doubleLock(out)

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Server.StopServer
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
