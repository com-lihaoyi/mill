package mill.daemon

import mill.api.daemon.Watchable
import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.internal.bsp.{BspServerHandle, BspServerResult}
import mill.api.daemon.internal.NonFatal
import mill.api.SystemStreams
import sun.misc.Signal

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

private[daemon] object BspMode {
  type RunBootstrap =
    (String, Option[RunnerLauncherState], SystemStreams, Boolean) => RunnerLauncherState

  // The bridge returns the bootstrap attempt's evaluators, watched set, errorOpt,
  // and an AutoCloseable that releases the underlying RunnerLauncherState. The
  // caller (MillBuildServer) owns the closeable and decides whether to release it
  // immediately (broken attempt) or retain it across requests (successful attempt
  // serving as fallback during a future broken attempt).
  type BootstrapBridge =
    String => (Seq[EvaluatorApi], Seq[Watchable], Option[String], AutoCloseable)

  def run(
      streams: SystemStreams,
      runMillBootstrap: RunBootstrap,
      startBspServer: BootstrapBridge => (BspServerHandle, IdeWorkerSupport.BspBuildClient)
  ): Boolean = {
    Signal.handle(
      new Signal("TERM"),
      _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
    )

    val bspPrevState = new AtomicReference[Option[RunnerLauncherState]](None)

    val bootstrapBridge: BootstrapBridge = activeCommandMessage => {
      val newState = runMillBootstrap(activeCommandMessage, bspPrevState.get(), streams, true)
      if (newState.errorOpt.isEmpty && newState.finalFrame.isDefined)
        bspPrevState.set(Some(newState))
      (newState.allEvaluators, newState.watched, newState.errorOpt, newState)
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
