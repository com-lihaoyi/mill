package mill.daemon

import mill.constants.{DaemonFiles, OutFiles, Util}
import mill.daemon.MillMain0.{handleMillException, main0}
import mill.api.BuildCtx
import mill.server.Server

import scala.jdk.CollectionConverters.*
import scala.util.Properties

object MillNoDaemonMain {
  def main(args0: Array[String]): Unit = mill.api.SystemStreamsUtils.withTopLevelSystemStreamProxy {
    val initialSystemStreams = mill.api.SystemStreams.original

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    if (Properties.isWin)
      // temporarily disabling FFM use by coursier, which has issues with the way
      // Mill manages class loaders, throwing things like
      // UnsatisfiedLinkError: Native Library C:\Windows\System32\ole32.dll already loaded in another classloader
      sys.props("coursier.windows.disable-ffm") = "true"

    val args = MillDaemonMain.Args(getClass.getName, args0)
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

    val (result, _) =
      try main0(
          args = args.rest.toArray,
          stateCache = RunnerState.empty,
          mainInteractive = mill.constants.Util.hasConsole(),
          streams0 = initialSystemStreams,
          env = System.getenv().asScala.toMap,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          systemExit = ( /*reason*/ _, exitCode) => sys.exit(exitCode),
          daemonDir = args.daemonDir,
          outLock = outLock
        )
      catch handleMillException(initialSystemStreams.err, ())

    System.exit(if (result) 0 else 1)
  }

}
