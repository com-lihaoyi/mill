package mill.bsp

import mill.api.{Ctx, DummyInputStream, Logger, Result, SystemStreams, internal}
import mill.eval.Evaluator

import java.io.PrintStream
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

object BspContext {
  val bspServerHandle = Promise[BspServerHandle]()
}

@internal
class BspContext(streams: SystemStreams, bspLogStream: Option[PrintStream], home: os.Path) {
  // BSP mode, run with a simple evaluator command to inject the evaluator
  // The command returns when the server exists or the workspace should be reloaded
  // if the `lastResult` is `ReloadWorkspace` we re-run the script in a loop

  //              import scala.concurrent.ExecutionContext.Implicits._
  val serverThreadContext =
    ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  streams.err.println("Running in BSP mode with hardcoded startSession command")

  streams.err.println("Trying to load BSP server...")
  val bspServerFuture = Future {
    try {
      startBspServer(
        initialEvaluator = None,
        streams = streams,
        logStream = bspLogStream,
        canReload = true
      )
    } catch {
      case NonFatal(e) =>
        streams.err.println(s"Could not start BSP server. ${e.getMessage}")
        e.printStackTrace(streams.err)
        BspServerResult.Failure
    }
  }(serverThreadContext)

  val handle = Await.result(
    BspContext.bspServerHandle.future,
    Duration(10, TimeUnit.SECONDS)
  )

  streams.err.println("BSP server started")

  def startBspServer(
      initialEvaluator: Option[Evaluator],
      streams: SystemStreams,
      logStream: Option[PrintStream],
      canReload: Boolean
  ): BspServerResult = {
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
    }

    val worker = BspWorker(os.pwd, home, log)

    worker match {
      case Result.Success(worker) =>
        worker.startBspServer(
          initialEvaluator,
          streams,
          logStream.getOrElse(streams.err),
          home / Constants.bspDir,
          canReload,
          Seq(BspContext.bspServerHandle)
        )
      case f: Result.Failure[_] =>
        streams.err.println("Failed to start the BSP worker. " + f.msg)
        BspServerResult.Failure
      case f: Result.Exception =>
        streams.err.println("Failed to start the BSP worker. " + f.throwable)
        BspServerResult.Failure
      case f =>
        streams.err.println("Failed to start the BSP worker. " + f)
        BspServerResult.Failure
    }
  }
}
