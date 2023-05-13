package mill.bsp

import mill.api.{SystemStreams, internal}

import java.io.PrintStream
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

@internal
class BspContext(streams: SystemStreams, bspLogStream: Option[PrintStream], home: os.Path) {
  // BSP mode, run with a simple evaluator command to inject the evaluator
  // The command returns when the server exists or the workspace should be reloaded
  // if the `lastResult` is `ReloadWorkspace` we re-run the script in a loop

  //              import scala.concurrent.ExecutionContext.Implicits._
  val serverThreadContext =
    ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  streams.err.println("Running in BSP mode with hardcoded startSession command")

  val bspServerHandle = Promise[BspServerHandle]()

  streams.err.println("Trying to load BSP server...")
  val bspServerFuture = Future {
    try {
      mill.bsp.BSP.startBspServer(
        initialEvaluator = None,
        streams = streams,
        logStream = bspLogStream,
        workspaceDir = os.pwd,
        ammoniteHomeDir = home,
        canReload = true,
        serverHandle = Some(bspServerHandle)
      )
    } catch {
      case NonFatal(e) =>
        streams.err.println(s"Could not start BSP server. ${e.getMessage}")
        e.printStackTrace(streams.err)
        BspServerResult.Failure
    }
  }(serverThreadContext)

  val handle = Await.result(bspServerHandle.future, Duration(10, TimeUnit.SECONDS)).tap { _ =>
    streams.err.println("BSP server started")
  }

  val millArgs = List("mill.bsp.BSP/startSession")
}
