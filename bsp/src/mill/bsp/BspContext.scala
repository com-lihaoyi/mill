package mill.bsp

import mill.api.{DummyInputStream, Logger, Result, SystemStreams}

import java.io.PrintStream
import scala.util.control.NonFatal

private[mill] class BspContext(
    streams: SystemStreams,
    bspLogStream: Option[PrintStream],
    home: os.Path
) {
  // BSP mode, run with a simple evaluator command to inject the evaluator
  // The command returns when the server exists or the workspace should be reloaded
  // if the `lastResult` is `ReloadWorkspace` we re-run the script in a loop

  streams.err.println("Running in BSP mode with hardcoded startSession command")

  streams.err.println("Trying to load BSP server...")
  val bspServerHandle =
    try {
      startBspServer(
        streams0 = streams,
        logStream = bspLogStream,
        canReload = true
      ).get
    } catch {
      case NonFatal(e) =>
        streams.err.println(s"Could not start BSP server. ${e.getMessage}")
        throw e
    }

  streams.err.println("BSP server started")

  def startBspServer(
      streams0: SystemStreams,
      logStream: Option[PrintStream],
      canReload: Boolean
  ): Result[BspServerHandle] = {
    val log: Logger = new Logger {
      override def streams: SystemStreams = new SystemStreams(
        out = streams0.out,
        err = streams0.err,
        in = DummyInputStream
      )
      def prompt = new Logger.Prompt.NoOp {
        override def setPromptDetail(key: Seq[String], s: String): Unit = streams.err.println(s)
      }

      override def info(s: String): Unit = streams.err.println(s)
      override def warn(s: String): Unit = streams.err.println(s)
      override def error(s: String): Unit = streams.err.println(s)
      override def ticker(s: String): Unit = streams.err.println(s)

      override def debug(s: String): Unit = streams.err.println(s)
    }

    BspWorker(mill.api.WorkspaceRoot.workspaceRoot, home, log).flatMap { worker =>
      os.makeDir.all(home / Constants.bspDir)
      worker.startBspServer(
        mill.api.WorkspaceRoot.workspaceRoot,
        streams,
        logStream.getOrElse(streams.err),
        home / Constants.bspDir,
        canReload
      )
    }
  }
}
