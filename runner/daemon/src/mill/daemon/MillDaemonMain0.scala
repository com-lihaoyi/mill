package mill.daemon

import mill.api.{BuildCtx, SystemStreams}
import mill.client.lock.Locks
import mill.constants.OutFolderMode
import mill.internal.{LauncherLockRegistry, LauncherOutFilesState, OutputDirectoryLayout}
import mill.server.Server

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Properties, Success, Try}

object MillDaemonMain0 {
  case class Args(
      daemonDir: os.Path,
      outMode: OutFolderMode,
      useFileLocks: Boolean,
      rest: Seq[String]
  )
  object Args {
    def apply(appName: String, args: Array[String]): Either[String, Args] = {
      def usage(extra: String = "") =
        s"usage: $appName <daemon-dir> <out-mode> <use-file-locks> <mill-args>$extra"

      args match {
        case Array(daemonDir, outModeStr, useFileLocksStr, rest*) =>
          Try(OutFolderMode.fromString(outModeStr)) match {
            case Failure(_) =>
              val possibleValues = OutFolderMode.values.map(_.asString).mkString(", ")
              Left(usage(s"\n\n<out-mode> must be one of $possibleValues but was '$outModeStr'"))
            case Success(outMode) =>
              val useFileLocks = useFileLocksStr.toBoolean
              Right(apply(os.Path(daemonDir), outMode, useFileLocks, rest))
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

    // temporarily disabling FFM use by coursier, which has issues with the way
    // Mill manages class loaders, throwing things like
    // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
    if (Properties.isWin) sys.props("coursier.windows.disable-ffm") = "true"

    coursier.Resolve.proxySetup() // Take into account proxy-related Java properties

    mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
      Server.overrideSigIntHandling()

      val acceptTimeout =
        Try(System.getProperty("mill.server_timeout").toInt.millis).getOrElse(30.minutes)

      val exitCode = new MillDaemonMain0(
        daemonDir = args.daemonDir,
        acceptTimeout = acceptTimeout,
        Locks.forDirectory(args.daemonDir.toString, args.useFileLocks),
        outMode = args.outMode
      ).run().getOrElse(0)

      System.exit(exitCode)
    }
  }
}

class MillDaemonMain0(
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    outMode: OutFolderMode
) extends mill.server.MillDaemonServer(daemonDir, acceptTimeout, locks) {

  private val processEnv = System.getenv().asScala.toMap
  private val outFolder =
    os.Path(
      OutputDirectoryLayout.outDir(outMode, BuildCtx.workspaceRoot, processEnv),
      BuildCtx.workspaceRoot
    )
  private val sharedOutLockManager =
    new SharedOutLockManager(MillMain0.outFileLock(outFolder), outFolder)
  Runtime.getRuntime.addShutdownHook(new Thread(() =>
    try sharedOutLockManager.close()
    catch { case _: Throwable => () }
  ))

  private val sharedState =
    new java.util.concurrent.atomic.AtomicReference[RunnerSharedState](
      RunnerSharedState.empty
    )

  private val lockRegistry = new LauncherLockRegistry
  private val outFilesState = new LauncherOutFilesState

  def main0(
      args: Array[String],
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      launcherPid: Long,
      setIdle: Boolean => Unit,
      setRunningCommand: Option[String] => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Server.StopServer,
      serverToClient: mill.rpc.MillRpcChannel[mill.launcher.DaemonRpc.ServerToClient],
      millRepositories: Seq[String]
  ): Boolean = {
    // Create runner that sends subprocess requests to the launcher via RPC
    val launcherRunner: mill.api.daemon.LauncherSubprocess.Runner =
      config =>
        serverToClient(mill.launcher.DaemonRpc.ServerToClient.RunSubprocess(config)).exitCode

    try MillMain0.main0(
        args = args,
        sharedState = sharedState,
        lockRegistry = lockRegistry,
        outFilesState = outFilesState,
        mainInteractive = mainInteractive,
        streams0 = streams,
        env = env,
        launcherPid = launcherPid,
        setIdle = setIdle,
        setRunningCommand = setRunningCommand,
        userSpecifiedProperties0 = userSpecifiedProperties,
        initialSystemProperties = initialSystemProperties,
        systemExit = systemExit,
        daemonDir = daemonDir,
        sharedOutLockManager = sharedOutLockManager,
        launcherSubprocessRunner = launcherRunner,
        serverToClientOpt = Some(serverToClient),
        millRepositories = millRepositories
      )
    catch {
      // Let InterruptedException propagate without printing (used by deferredStopServer for shutdown)
      case e: InterruptedException => throw e
      case e if MillMain0.handleMillException(streams.err).isDefinedAt(e) =>
        MillMain0.handleMillException(streams.err)(e)
    }
  }
}
