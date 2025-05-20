package mill.daemon

import mill.client.lock.{DoubleLock, Lock}
import mill.constants.{DaemonFiles, OutFiles, Util}
import mill.daemon.MillMain0.{handleMillException, main0, outMemoryLock}
import mill.define.BuildCtx
import mill.server.Server

import scala.util.Properties

object MillNoDaemonMain {
  def main(args: Array[String]): Unit = mill.define.SystemStreams.withTopLevelSystemStreamProxy {
    val initialSystemStreams = mill.define.SystemStreams.original

    if (Properties.isWin && Util.hasConsole())
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val processId = Server.computeProcessId()
    val out = os.Path(OutFiles.out, BuildCtx.workspaceRoot)
    Server.watchProcessIdFile(
      out / OutFiles.millNoDaemon / processId / DaemonFiles.processId,
      processId,
      running = () => true,
      exit = msg => {
        System.err.println(msg)
        System.exit(0)
      }
    )

    val outLock = new DoubleLock(
      outMemoryLock,
      Lock.file((out / OutFiles.millOutLock).toString)
    )

    val daemonDir = os.Path(args.head)
    val (result, _) =
      try main0(
          args = args.tail,
          stateCache = RunnerState.empty,
          mainInteractive = mill.constants.Util.hasConsole(),
          streams0 = initialSystemStreams,
          env = System.getenv().asScala.toMap,
          setIdle = _ => (),
          userSpecifiedProperties0 = Map(),
          initialSystemProperties = sys.props.toMap,
          systemExit = i => sys.exit(i),
          daemonDir = daemonDir,
          outLock = outLock
        )
      catch handleMillException(initialSystemStreams.err, ())

    System.exit(if (result) 0 else 1)
  }

}
