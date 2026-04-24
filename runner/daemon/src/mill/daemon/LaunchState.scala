package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.daemon.internal.{EvaluatorApi, internal, PathRefApi, TaskApi}
import mill.api.internal.RootModule
import mill.api.daemon.Watchable
import mill.api.daemon.WorkspaceLocking
import upickle.{ReadWriter, macroRW}

/**
 * Per-launcher bootstrap state, returned from [[MillBuildBootstrap.evaluate]]
 * and threaded through one launcher's lifetime (across `--watch` iterations,
 * BSP sessions, etc). Distinct from [[SharedMetaBuildState]], which holds the
 * deterministic meta-build outputs shared across concurrent launchers.
 *
 * A [[LaunchState]] carries:
 *
 * - [[metaBuildOverlays]] — per-depth [[MetaBuildOverlay]]s produced by this
 *   launcher's bootstrap. Each overlay points at the [[SharedMetaBuildState.ReusableFrame]]
 *   this launcher actually used (not necessarily the currently-published one,
 *   which another launcher may have replaced since), plus this launcher's
 *   evaluator, watches, and meta-build read lease.
 * - [[finalFrame]] — depth + [[FinalFrame]] for the level where user-visible
 *   tasks ran. At most one.
 * - [[bootstrapModuleOpt]] / [[buildFile]] / [[bootstrapEvalWatched]] — state
 *   from bootstrap module instantiation, tracked separately so a bootstrap
 *   failure still surfaces its watches.
 * - [[closeables]] — additional per-launcher resources (e.g. the workspace
 *   lock manager) closed when the state is closed.
 *
 * If evaluation fails before reaching the requested depth, [[errorOpt]] is set
 * and [[finalFrame]] is [[None]].
 */
@internal
case class LaunchState(
    bootstrapModuleOpt: Option[RootModule] = None,
    errorOpt: Option[String] = None,
    buildFile: Option[String] = None,
    // Watches captured during bootstrap module instantiation. Tracked separately because
    // a bootstrap failure produces no overlays to carry them.
    bootstrapEvalWatched: Seq[Watchable] = Nil,
    metaBuildOverlays: Map[Int, LaunchState.MetaBuildOverlay] = Map.empty,
    finalFrame: Option[(Int, LaunchState.FinalFrame)] = None,
    closeables: Seq[AutoCloseable] = Nil
) extends Watching.Result
    with AutoCloseable {
  import LaunchState.*

  def withMetaBuildOverlay(depth: Int, overlay: MetaBuildOverlay): LaunchState =
    copy(metaBuildOverlays = metaBuildOverlays.updated(depth, overlay))

  def overlayAt(depth: Int): Option[MetaBuildOverlay] = metaBuildOverlays.get(depth)

  def withFinalFrame(depth: Int, frame: FinalFrame): LaunchState =
    copy(finalFrame = Some(depth -> frame))

  def withError(err: String): LaunchState = copy(errorOpt = Some(err))

  def withCloseable(closeable: AutoCloseable): LaunchState =
    copy(closeables = closeable +: closeables)

  /**
   * Module-level watches captured at `depth` during this launcher's run. Used
   * to decide whether a shared classloader at the depth above needs refreshing.
   */
  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    overlayAt(depth).map(_.moduleWatched)
      .orElse(finalFrame.collect { case (`depth`, frame) => frame.moduleWatched })

  /** All watches this launcher accumulated — drives `--watch` re-runs. */
  def watched: Seq[Watchable] =
    sortedOverlays.flatMap { case (_, o) => o.evalWatched ++ o.moduleWatched } ++
      finalFrame.toSeq.flatMap { case (_, f) => f.evalWatched ++ f.moduleWatched } ++
      bootstrapEvalWatched

  /**
   * Live evaluators ordered final-first, then meta-build overlays from
   * shallowest to deepest. BSP script discovery and IDE generation walk this
   * in order and expect the workspace evaluator at `headOption`.
   */
  def allEvaluators: Seq[EvaluatorApi] =
    finalFrame.map(_._2.evaluator).toSeq ++ sortedOverlays.map(_._2.evaluator)

  override def close(): Unit = {
    // Evaluators stay alive after bootstrap evaluation so BSP/IDE follow-up can
    // execute more tasks against the returned state. Closing them here only
    // tears down per-run execution resources (e.g. profile loggers); workers live
    // in the process-level SharedWorkerCache and must remain shared.
    //
    // Order matters: evaluators first (they may read from the classloader
    // protected by the meta-build read lease), then leases (releasing them
    // unblocks concurrent writers that may close the classloader), then
    // closeables (e.g. the workspace lock manager itself).
    closeAll(
      allEvaluators.distinct ++
        sortedOverlays.flatMap { case (_, o) => o.metaBuildReadLease } ++
        closeables
    )
  }

  private def sortedOverlays: Seq[(Int, MetaBuildOverlay)] =
    metaBuildOverlays.toSeq.sortBy(_._1)

  private def closeAll(resources: Iterable[AutoCloseable]): Unit = {
    var firstError: Throwable = null
    for (resource <- resources) {
      try resource.close()
      catch {
        case e: Throwable =>
          if (firstError == null) firstError = e
          else firstError.addSuppressed(e)
      }
    }
    if (firstError != null) throw firstError
  }
}

object LaunchState {
  def empty: LaunchState = LaunchState()

  /**
   * One launcher's per-depth meta-build overlay over a [[SharedMetaBuildState.ReusableFrame]].
   *
   * [[reusable]] points at the frame this launcher is actually bound to. Normally
   * this equals the currently-published shared frame, but another launcher may
   * have replaced the shared frame since — in which case this overlay still
   * references the older frame it used during evaluation. [[reusable]] is [[None]]
   * if the meta-build compile failed at this depth; the overlay is still retained
   * so [[evalWatched]] / [[moduleWatched]] can drive a `--watch` re-run.
   *
   * [[metaBuildReadLease]] is held for the launcher's lifetime so concurrent
   * writers cannot close [[reusable]]'s classloader while we're still using it.
   */
  @internal
  case class MetaBuildOverlay(
      reusable: Option[SharedMetaBuildState.ReusableFrame],
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      metaBuildReadLease: Option[WorkspaceLocking.Lease] = None
  )

  object MetaBuildOverlay {
    /** An overlay for a meta-build that failed to compile: no [[SharedMetaBuildState.ReusableFrame]]. */
    def failed(
        evaluator: EvaluatorApi,
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable]
    ): MetaBuildOverlay = MetaBuildOverlay(
      reusable = None,
      evaluator = evaluator,
      evalWatched = evalWatched,
      moduleWatched = moduleWatched
    )
  }

  /**
   * The frame at `requestedDepth` where user-visible tasks ran. Carries only
   * per-launcher data: the evaluator and the watched sets recorded during this
   * evaluation. There is at most one per launcher; its depth is the `Int` in
   * [[LaunchState.finalFrame]].
   */
  @internal
  case class FinalFrame(
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable]
  )

  object Frame {
    case class WorkerInfo(identityHashCode: Int, inputHash: Int)
    implicit val workerInfoRw: ReadWriter[WorkerInfo] = macroRW

    def summarizeWorkerCache(
        workerCache: collection.Map[String, (Int, Val, TaskApi[?])]
    ): Map[String, WorkerInfo] = workerCache.synchronized {
      workerCache.iterator.map { case (k, (i, v, _)) =>
        (k, WorkerInfo(System.identityHashCode(v), i))
      }.toMap
    }

    /** Simplified representation of a frame, written to disk for debugging. */
    case class Logged(
        workerCache: Map[String, WorkerInfo],
        evalWatched: Seq[os.Path],
        moduleWatched: Seq[os.Path],
        classLoaderIdentity: Option[Int],
        runClasspath: Seq[(os.Path, Int)],
        runClasspathHash: Int
    )
    implicit val loggedRw: ReadWriter[Logged] = macroRW

    def loggedForMetaBuild(
        overlay: MetaBuildOverlay
    ): Logged = {
      val workerCache = overlay.reusable.map(_.workerCacheSummary).getOrElse(Map.empty)
      val classLoaderIdentity = overlay.reusable.map(_.classLoader.identity)
      val runClasspath = overlay.reusable.map(_.runClasspath).getOrElse(Nil)
      build(workerCache, overlay.evalWatched, overlay.moduleWatched, classLoaderIdentity, runClasspath)
    }

    def loggedForFinal(frame: FinalFrame): Logged =
      build(Map.empty, frame.evalWatched, frame.moduleWatched, None, Nil)

    private def build(
        workerCache: Map[String, WorkerInfo],
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable],
        classLoaderIdentity: Option[Int],
        runClasspath: Seq[PathRefApi]
    ): Logged = {
      def paths(ws: Seq[Watchable]) =
        ws.collect { case Watchable.Path(p, _, _) => os.Path(p) }.distinct
      Logged(
        workerCache,
        paths(evalWatched),
        paths(moduleWatched),
        classLoaderIdentity,
        runClasspath.map(p => os.Path(p.javaPath) -> p.sig),
        runClasspath.hashCode()
      )
    }
  }
}
