package mill.entrypoint

import mill.api.internal
import mill.main.{BspServerHandle, BspServerResult, BspServerStarter}
import mill.util.SystemStreams

import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

@internal
class BspContext(streams: SystemStreams, home: os.Path) {
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
      BspServerStarter().startBspServer(
        initialEvaluator = None,
        streams = streams,
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

  val handle = Await.result(bspServerHandle.future, Duration.Inf).tap { _ =>
    streams.err.println("BSP server started")
  }

  val millArgs = List("mill.bsp.BSP/startSession")
}
