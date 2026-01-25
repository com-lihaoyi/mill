package mill.daemon

import mill.constants.{DaemonFiles, Util}
import mill.constants.OutFiles.OutFiles
import mill.daemon.MillMain0.handleMillException
import mill.api.BuildCtx
import mill.launcher.DaemonRpc
import mill.server.Server

import scala.jdk.CollectionConverters.*
import scala.util.Properties

object MillNoDaemonMain0 {
  /** Exception used to signal early exit while allowing finally blocks to run */
  private case class EarlyExitException(exitCode: Int) extends Exception

  def main(args0: Array[String]): Unit = mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
    val initialSystemStreams = mill.api.SystemStreams.original

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    if (Properties.isWin)
      // temporarily disabling FFM use by coursier, which has issues with the way
      // Mill manages class loaders, throwing things like
      // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
      sys.props("coursier.windows.disable-ffm") = "true"

    // Take into account proxy-related Java properties
    coursier.Resolve.proxySetup()

    val args = MillDaemonMain0.Args(getClass.getName, args0)
      .fold(err => throw IllegalArgumentException(err), identity)

    val processId = Server.computeProcessId()
    val out = os.Path(OutFiles.outFor(args.outMode), BuildCtx.workspaceRoot)
    Server.watchProcessIdFile(
      out / OutFiles.millNoDaemon / s"pid-$processId" / DaemonFiles.processId,
      processId,
      running = () => true,
      exit = msg => {
        System.err.println(msg)
        System.exit(0)
      }
    )

    val outLock = MillMain0.doubleLock(out)

    // Create runner that executes subprocesses locally with inherited I/O
    val launcherRunner: mill.api.daemon.LauncherSubprocess.Runner =
      config =>
        DaemonRpc.defaultRunSubprocess(DaemonRpc.ServerToClient.RunSubprocess(config)).exitCode

    // Run with worker tracking - workers are automatically closed on exit
    val exitCode = MillDaemonMain0.withWorkerTracking {
      try {
        val (res, _) = MillMain0.main0(
          args = args.rest.toArray,
          stateCache = RunnerState.empty,
          mainInteractive = mill.constants.Util.hasConsole(),
          streams0 = initialSystemStreams,
          env = System.getenv().asScala.toMap,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          // Throw exception instead of calling sys.exit() directly,
          // so finally block can run to close workers before exiting
          systemExit = ( /*reason*/ _, code) => throw EarlyExitException(code),
          daemonDir = args.daemonDir,
          outLock = outLock,
          launcherSubprocessRunner = launcherRunner
        )
        if (res) 0 else 1
      } catch {
        case EarlyExitException(code) => code
        case other => handleMillException(initialSystemStreams.err, 1)(other)._2
      }
    }()

    System.exit(exitCode)
  }

}
