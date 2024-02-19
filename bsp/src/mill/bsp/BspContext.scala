package mill.bsp

import mill.api.{DummyInputStream, Logger, SystemStreams}

import java.io.PrintStream
import scala.util.control.NonFatal

private[mill] object BspContext {
  @volatile var bspServerHandle: BspServerHandle = null
}

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
  BspContext.bspServerHandle =
    try {
      startBspServer(
        streams = streams,
        logStream = bspLogStream,
        canReload = true
      ) match {
        case Left(err) => sys.error(err)
        case Right(res) => res
      }
    } catch {
      case NonFatal(e) =>
        streams.err.println(s"Could not start BSP server. ${e.getMessage}")
        throw e
    }

  streams.err.println("BSP server started")

  def startBspServer(
      streams: SystemStreams,
      logStream: Option[PrintStream],
      canReload: Boolean
  ): Either[String, BspServerHandle] = {
    val log: Logger = new Logger {
      override def colored: Boolean = false
      override def systemStreams: SystemStreams = new SystemStreams(
        out = streams.out,
        err = streams.err,
        in = DummyInputStream
      )

      override def info(s: String): Unit = streams.err.println(s)
      override def error(s: String): Unit = streams.err.println(s)
      override def ticker(s: String): Unit = streams.err.println(s)
      override def debug(s: String): Unit = streams.err.println(s)

      override def debugEnabled: Boolean = true

      override def rawOutputStream: PrintStream = systemStreams.out
    }

    BspWorker(os.pwd, home, log).flatMap { worker =>
      os.makeDir.all(home / Constants.bspDir)
      worker.startBspServer(
        streams,
        logStream.getOrElse(streams.err),
        home / Constants.bspDir,
        canReload,
        os.pwd
      )
    }
  }
}
