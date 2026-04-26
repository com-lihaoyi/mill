package mill.daemon

import mill.api.daemon.Watchable
import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.internal.bsp.{BspServerHandle, BspServerResult}
import mill.api.daemon.internal.NonFatal
import mill.api.SystemStreams
import sun.misc.Signal

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Using}

private[daemon] object BspMode {
  type RunBootstrap =
    (String, Option[RunnerLauncherState], SystemStreams, Boolean) => RunnerLauncherState

  type BootstrapBridge = [T] => (
      String,
      (Seq[EvaluatorApi], Seq[Watchable], Option[String]) => T
  ) => T

  def run(
      streams: SystemStreams,
      runMillBootstrap: RunBootstrap,
      startBspServer: BootstrapBridge => (BspServerHandle, IdeWorkerSupport.BspBuildClient)
  ): Boolean = {
    Signal.handle(
      new Signal("TERM"),
      _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
    )

    // Each BSP request bootstraps fresh evaluators with no shared `prevState`:
    // BSP requests run concurrently on `bspRequestExecutor`, and a shared
    // `RunnerLauncherState` would be unsafe (its evaluators could be closed by
    // one thread's `Using.resource` while another still references them).
    // The daemon-wide RunnerSharedState already caches reusable meta-build
    // frames across requests under proper locking.
    val bootstrapBridge: BootstrapBridge = [T] =>
      (activeCommandMessage, body) =>
        Using.resource(
          runMillBootstrap(activeCommandMessage, None, streams, true)
        ) { runnerState =>
          body(runnerState.allEvaluators, runnerState.watched, runnerState.errorOpt)
      }

    val (bspServerHandle, _) = startBspServer(bootstrapBridge)

    val shutdownResult =
      try Success(Await.result(bspServerHandle.shutdownFuture, Duration.Inf))
      catch { case NonFatal(ex) => Failure(ex) }

    val errored = shutdownResult match {
      case Failure(ex) =>
        streams.err.println("BSP server threw an exception, exiting")
        ex.printStackTrace(streams.err)
        true
      case Success(BspServerResult.Shutdown) =>
        streams.err.println("BSP shutdown asked by client, exiting")
        streams.in.close()
        false
      case Success(BspServerResult.ReloadWorkspace) =>
        streams.err.println("BSP reload asked by client, exiting")
        streams.in.close()
        false
    }

    bspServerHandle.close()
    streams.err.println("Exiting BSP runner loop")
    !errored
  }
}
