package mill.daemon

import mill.api.{PathRef, SystemStreams}
import mill.client.ClientUtil
import mill.client.lock.{Lock, Locks}
import mill.constants.OutFolderMode
import mill.server.Server

import scala.concurrent.duration.*
import scala.util.{Failure, Properties, Success, Try}

object MillDaemonMain {
  case class Args(daemonDir: os.Path, outMode: OutFolderMode, outDir: os.Path, rest: Seq[String])
  object Args {
    def apply(appName: String, args: Array[String]): Either[String, Args] = {
      def usage(extra: String = "") =
        s"usage: $appName <daemon-dir> <out-mode> <out-dir> <mill-args>$extra"

      args match {
        case Array(daemonDir, outModeStr, outDir, rest*) =>
          Try(OutFolderMode.fromString(outModeStr)) match {
            case Failure(_) =>
              val possibleValues = OutFolderMode.values.map(_.asString).mkString(", ")
              Left(usage(s"\n\n<out-mode> must be one of $possibleValues but was '$outModeStr'"))
            case Success(outMode) =>
              Right(apply(os.Path(daemonDir), outMode, os.Path(outDir), rest))
          }
        case _ => Left(usage())
      }
    }
  }

  def main(args0: Array[String]): Unit = {

    // Set by an integration test
    if (System.getenv("MILL_DAEMON_CRASH") == "true")
      sys.error("Mill daemon early crash requested")

    val args =
      Args(getClass.getName, args0).fold(err => throw IllegalArgumentException(err), identity)

    PathRef.outPathOverride.withValue(Some(args.outDir)) {
      if (Properties.isWin)
        // temporarily disabling FFM use by coursier, which has issues with the way
        // Mill manages class loaders, throwing things like
        // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
        sys.props("coursier.windows.disable-ffm") = "true"

      // Take into account proxy-related Java properties
      coursier.Resolve.proxySetup()

      mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
        Server.overrideSigIntHandling()

        val acceptTimeout =
          Try(System.getProperty("mill.server_timeout").toInt.millis).getOrElse(30.minutes)

        new MillDaemonMain(
          daemonDir = args.daemonDir,
          acceptTimeout = acceptTimeout,
          Locks.files(args.daemonDir.toString),
          outMode = args.outMode,
          outDir = args.outDir
        ).run()

        System.exit(ClientUtil.ExitServerCodeWhenIdle())
      }
    }
  }
}
class MillDaemonMain(
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    outMode: OutFolderMode,
    outDir: os.Path
) extends mill.server.MillDaemonServer[RunnerState](
      daemonDir = daemonDir,
      acceptTimeout = acceptTimeout,
      locks = locks,
      outDir = outDir
    ) {

  def stateCache0 = RunnerState.empty

  val outLock = MillMain0.doubleLock(outDir)

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
        outLock = outLock,
        outDir = outDir
      )
    catch MillMain0.handleMillException(streams.err, stateCache)
  }
}
