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

/**
 * Runs the BSP-mode entry point: wires a single [[BspBootstrapBridge]] over
 * [[runMillBootstrap]], starts the BSP server, awaits its shutdown, and
 * returns the final exit success boolean. Lifted out of [[MillMain0]] to
 * keep the main flow flat — the only logic specific to BSP lives here.
 */
private[daemon] object BspMode {

  /**
   * @param runMillBootstrap factory that runs one bootstrap and returns the
   *   resulting [[RunnerLauncherState]]. The caller has already captured all
   *   the per-launcher context (config flags, streams, daemon dir, lock
   *   manager); we just call it for each BSP request.
   * @param startBspServer factory that, given the wired bridge, brings up
   *   the BSP server and returns its handle + the connected `BuildClient`.
   */
  def run(
      streams: SystemStreams,
      runMillBootstrap: (
          /*skipSelectiveExecution*/ Boolean,
          /*prevState*/ Option[RunnerLauncherState],
          /*tasksAndParams*/ Seq[String],
          /*streams*/ SystemStreams,
          /*millActiveCommandMessage*/ String,
          /*reporter*/ EvaluatorApi => Int => Option[CompileProblemReporter],
          /*useBspRequestLogger*/ Boolean
      ) => RunnerLauncherState,
      startBspServer: BspBootstrapBridge => (BspServerHandle, IdeWorkerSupport.BspBuildClient)
  ): Boolean = {
    // Can happen if a concurrent BSP server starts and shuts us down.
    // Logged so users (and tests) see why we exited.
    Signal.handle(
      new Signal("TERM"),
      _ => SystemStreams.originalErr.println("Received SIGTERM, exiting")
    )

    // Each BSP request (and the worker-side watcher loop) calls into this
    // bridge to obtain fresh evaluators for one bootstrap's lifetime.
    // `Using.resource` tears down the resulting RunnerLauncherState — meta-
    // build read leases, retained per-task read leases, evaluators — before
    // the bridge call returns. No shared mutable evaluator cache survives
    // across bridge calls; concurrent CLI launchers and concurrent BSP
    // requests each get their own RunnerLauncherState and share the cached
    // meta-build classloader via RunnerSharedState, serializing at lock
    // granularity via processRunClasspath's read-first speculation.
    //
    // Promise-backed so `bridgeReporter` blocks until the BuildClient is
    // wired in. Without this, an early watcher iteration that fires between
    // startBspServer returning and the .set call would silently drop
    // diagnostics from the very first compile after build/initialize.
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

    // Cross-request snapshot of the most recent bootstrap. Used by
    // MillBuildBootstrap.processFinalTasks to short-circuit (skip running
    // `resolve _`) when nothing has changed. The reference holds an
    // already-closed [[RunnerLauncherState]]: meta-build read leases and
    // evaluators have been torn down, but the case-class data fields
    // (errorOpt, finalFrame, etc.) remain readable per
    // [[RunnerLauncherState.close()]]'s post-close invariant. This keeps
    // an idle BSP server from blocking concurrent CLI meta-build writes.
    val bspPrevState = new AtomicReference[Option[RunnerLauncherState]](None)

    val bootstrapBridge = new BspBootstrapBridge {
      override def runBootstrap[T](
          activeCommandMessage: String,
          body: BspBootstrapBridge.Body[T]
      ): T = Using.resource(
        runMillBootstrap(
          /*skipSelectiveExecution=*/ false,
          /*prevState=*/ bspPrevState.get(),
          /*tasksAndParams=*/ Seq("resolve", "_"),
          /*streams=*/ streams,
          /*millActiveCommandMessage=*/ activeCommandMessage,
          /*reporter=*/ bridgeReporter,
          /*useBspRequestLogger=*/ true
        )
      ) { runnerState =>
        // Cache only successful bootstraps so the next request short-circuits
        // off real watches; on failure leave the prior snapshot in place.
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
        // should make the lsp4j-managed BSP server exit
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
