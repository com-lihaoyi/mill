package mill.daemon

import mill.api.daemon.internal.bsp.{BspBootstrapBridge, BspServerHandle, BspServerResult}
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi, NonFatal}
import mill.api.SystemStreams
import sun.misc.Signal

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Using}

private[daemon] object BspMode {

  def run(
      streams: SystemStreams,
      runMillBootstrap: (
      ) => RunnerLauncherState,
      startBspServer: BspBootstrapBridge => (BspServerHandle, IdeWorkerSupport.BspBuildClient)
  ): Boolean = {
    Signal.handle(
      new Signal("TERM"),
      _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
    )

    val bridgeBuildClientPromise = Promise[IdeWorkerSupport.BspBuildClient]()
    val bridgeReporter: EvaluatorApi => Int => Option[CompileProblemReporter] =
      ev => {
        val client = Await.result(bridgeBuildClientPromise.future, Duration.Inf)
        IdeWorkerSupport.bspReporterPool(
          workspaceDir = mill.api.BuildCtx.workspaceRoot,
          evaluators = Seq(ev),
          buildClient = client
        )
      }

    val bspPrevState = new AtomicReference[Option[RunnerLauncherState]](None)

    val bootstrapBridge = new BspBootstrapBridge {
      override def runBootstrap[T](
          activeCommandMessage: String,
          body: BspBootstrapBridge.Body[T]
      ): T = Using.resource(
        runMillBootstrap(
        )
      ) { runnerState =>
        if (runnerState.errorOpt.isEmpty && runnerState.finalFrame.isDefined)
          bspPrevState.set(Some(runnerState))
        body.apply(
          BspBootstrapBridge.BootstrapState(
            runnerState.allEvaluators.asJava,
            runnerState.watched.asJava,
            runnerState.errorOpt
          )
        )
      }
    }

    val (bspServerHandle, buildClient) = startBspServer(bootstrapBridge)
    bridgeBuildClientPromise.success(buildClient)

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
