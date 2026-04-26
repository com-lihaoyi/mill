package mill.daemon

import mill.constants.{DaemonFiles, Util}
import mill.constants.OutFiles.OutFiles
import mill.daemon.MillMain0.handleMillException
import mill.api.BuildCtx
import mill.internal.{LauncherArtifactState, LauncherLockRegistry, OutputDirectoryLayout}
import mill.launcher.DaemonRpc
import mill.server.Server

import scala.jdk.CollectionConverters.*
import scala.util.Properties

object MillNoDaemonMain0 {
  def main(args0: Array[String]): Unit = mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
    val initialSystemStreams = mill.api.SystemStreams.original

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    if (Properties.isWin)
      sys.props("coursier.windows.disable-ffm") = "true"

    coursier.Resolve.proxySetup()

    val args = MillDaemonMain0.Args(getClass.getName, args0)
      .fold(err => throw IllegalArgumentException(err), identity)

    val processId = Server.computeProcessId()
    val env = System.getenv().asScala.toMap
    val out = os.Path(
      OutputDirectoryLayout.outDir(args.outMode, BuildCtx.workspaceRoot, env),
      BuildCtx.workspaceRoot
    )
    Server.watchProcessIdFile(
      out / OutFiles.millNoDaemon / s"pid-$processId" / DaemonFiles.processId,
      processId,
      running = () => true,
      exit = msg => {
        System.err.println(msg)
        System.exit(0)
      }
    )

    val outLock = MillMain0.outFileLock(out)
    val sharedOutLockManager = new SharedOutLockManager(outLock, out)

    val launcherRunner: mill.api.daemon.LauncherSubprocess.Runner =
      config =>
        DaemonRpc
          .defaultRunSubprocessWithStreams(None)(DaemonRpc.ServerToClient.RunSubprocess(config))
          .exitCode

    val result =
      try MillMain0.main0(
          args = args.rest.toArray,
          sharedState = new java.util.concurrent.atomic.AtomicReference(RunnerSharedState.empty),
          lockRegistry = new LauncherLockRegistry,
          artifactState = new LauncherArtifactState,
          mainInteractive = mill.constants.Util.hasConsole(),
          streams0 = initialSystemStreams,
          env = env,
          launcherPid = processId,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          systemExit = ( /*reason*/ _, exitCode) => sys.exit(exitCode),
          daemonDir = args.daemonDir,
          sharedOutLockManager = sharedOutLockManager,
          launcherSubprocessRunner = launcherRunner,
          serverToClientOpt = None,
          millRepositories = Seq.empty
        )
      catch handleMillException(initialSystemStreams.err)
      finally
        try sharedOutLockManager.close()
        catch { case _: Throwable => () }

    System.exit(if (result) 0 else 1)
  }
}
