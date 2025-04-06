package mill.bsp

import mill.api.{DummyInputStream, Logger, Result, SystemStreams}
import mill.define.Evaluator

import java.io.PrintStream
import scala.util.control.NonFatal

object BspServerRunner {
  def runSession(
      streams0: SystemStreams,
      logStream: Option[PrintStream],
      evaluators: Seq[Evaluator]
  ): Result[BspServerResult] = {

    streams0.err.println("Trying to load BSP server...")
    val bspServerHandleRes = {
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

      val wsRoot = mill.api.WorkspaceRoot.workspaceRoot
      BspWorker(wsRoot, log).flatMap { worker =>
        val logDir = wsRoot / "mill-bsp.log"
        os.makeDir.all(logDir)
        worker.startBspServer(
          mill.api.WorkspaceRoot.workspaceRoot,
          streams0,
          logStream.getOrElse(streams0.err),
          logDir,
          true
        )
      }
    }

    streams0.err.println("BSP server started")

    bspServerHandleRes.map { bspServerHandle =>
      try bspServerHandle.runSession(evaluators)
      finally bspServerHandle.close()
    }

  }
}
