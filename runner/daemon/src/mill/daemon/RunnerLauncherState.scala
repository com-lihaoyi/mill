package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.daemon.internal.{EvaluatorApi, internal, PathRefApi, TaskApi}
import mill.api.daemon.Watchable
import mill.api.internal.WorkspaceLocking
import upickle.{ReadWriter, macroRW}

/**
 * Per-launcher bootstrap state, returned from [[MillBuildBootstrap.evaluate]]
 * and threaded through one launcher's lifetime (across `--watch` iterations,
 * BSP sessions, etc). Distinct from [[RunnerSharedState]], which holds the
 * deterministic meta-build outputs shared across concurrent launchers.
 *
 * A [[RunnerLauncherState]] carries:
 *
 * - [[metaBuildFrames]] — ordered shallowest-to-deepest, matching the
 *   bootstrap stack. Each frame carries the launcher-local evaluator and
 *   eval watches, plus the pinned shared-frame snapshot this launcher
 *   actually used (not necessarily the currently-published one, which
 *   another launcher may have replaced since), its meta-build read lease,
 *   and any per-run invalidation tree produced when that classloader was
 *   refreshed.
 * - [[finalFrame]] — the level where user-visible
 *   tasks ran. At most one.
 * - [[buildFile]] / [[bootstrapEvalWatched]] — per-launcher bootstrap-derived
 *   info (the root build file name found on disk and the [[Watchable]] for it).
 *   Tracked here so `--watch` can still surface the build file even when
 *   bootstrap fails. The actual bootstrap module lives on [[RunnerSharedState]],
 *   since it is deterministic in the workspace and cheap to share across launchers.
 * - [[closeables]] — additional per-launcher resources (e.g. the workspace
 *   lock manager) closed when the state is closed.
 *
 * If evaluation fails before reaching the requested depth, [[errorOpt]] is set
 * and [[finalFrame]] is [[None]].
 */
@internal
case class RunnerLauncherState(
    errorOpt: Option[String] = None,
    buildFile: Option[String] = None,
    // Watches captured during bootstrap module instantiation. Tracked separately because
    // a bootstrap failure produces no overlays to carry them.
    bootstrapEvalWatched: Seq[Watchable] = Nil,
    metaBuildFrames: List[RunnerLauncherState.MetaBuildFrame] = Nil,
    finalFrame: Option[RunnerLauncherState.FinalFrame] = None,
    closeables: Seq[AutoCloseable] = Nil
) extends Watching.Result
    with AutoCloseable {
  import RunnerLauncherState.*

  def withMetaBuildFrame(frame: MetaBuildFrame): RunnerLauncherState =
    copy(metaBuildFrames = frame +: metaBuildFrames)

  def metaBuildFrameAt(depth: Int): Option[MetaBuildFrame] =
    metaBuildFrames.find(_.depth == depth)

  def withFinalFrame(frame: FinalFrame): RunnerLauncherState =
    copy(finalFrame = Some(frame))

  def withError(err: String): RunnerLauncherState = copy(errorOpt = Some(err))

  def withCloseable(closeable: AutoCloseable): RunnerLauncherState =
    copy(closeables = closeable +: closeables)

  /** Total depths already processed in this launch: meta-builds + final frame. */
  def processedDepths: Int = metaBuildFrames.size + finalFrame.size

  /**
   * Module-level watches captured at `depth` during this launcher's run. Used
   * to decide whether a shared classloader at the depth above needs refreshing.
   */
  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    metaBuildFrameAt(depth).flatMap(_.sharedFrame.moduleWatched)
      .orElse(finalFrame.collect { case frame if frame.depth == depth => frame.moduleWatched })

  /** All watches this launcher accumulated — drives `--watch` re-runs. */
  def watched: Seq[Watchable] =
    metaBuildFrames.flatMap(f => f.evalWatched ++ f.sharedFrame.moduleWatched.getOrElse(Nil)) ++
      finalFrame.toSeq.flatMap(f => f.evalWatched ++ f.moduleWatched) ++
      bootstrapEvalWatched

  /**
   * Live evaluators ordered final-first, then meta-build overlays from
   * shallowest to deepest. BSP script discovery and IDE generation walk this
   * in order and expect the workspace evaluator at `headOption`.
   */
  def allEvaluators: Seq[EvaluatorApi] =
    finalFrame.map(_.evaluator).toSeq ++ metaBuildFrames.map(_.evaluator)

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
        metaBuildFrames.flatMap(_.metaBuildReadLease) ++
        closeables
    )
  }

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

object RunnerLauncherState {
  def empty: RunnerLauncherState = RunnerLauncherState()

  /**
   * One launcher's per-depth meta-build overlay over a [[RunnerSharedState.Frame]].
   *
   * [[sharedFrame]] points at the frame this launcher is actually bound to.
   * Normally this equals the currently-published shared frame, but another
   * launcher may have replaced the shared frame since — in which case this
   * overlay still references the older frame it used during evaluation.
   * [[sharedFrame]] may contain only [[RunnerSharedState.Frame.moduleWatched]]
   * when the meta-build compile failed at this depth; the overlay is still
   * retained so [[evalWatched]] and the stored module watches can drive a `--watch`
   * re-run.
   *
   * [[metaBuildReadLease]] is held for the launcher's lifetime so concurrent
   * writers cannot close [[sharedFrame]]'s classloader while we're still using it.
   */
  @internal
  case class MetaBuildFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      sharedFrame: RunnerSharedState.Frame,
      metaBuildReadLease: Option[WorkspaceLocking.Lease] = None,
      // Only populated on runs that refreshed the classloader at this depth.
      // Reused classloaders intentionally do not carry a stale invalidation tree forward.
      spanningInvalidationTree: Option[String] = None
  ) {
    def logged: Frame.Logged = Frame.build(
      sharedFrame.workerCacheSummary,
      evalWatched,
      sharedFrame.moduleWatched.getOrElse(Nil),
      sharedFrame.classLoaderOpt.map(_.identity),
      sharedFrame.runClasspath
    )
  }

  object MetaBuildFrame {

    /** An overlay for a meta-build that failed to compile: no reusable payload. */
    def failed(
        depth: Int,
        evaluator: EvaluatorApi,
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable]
    ): MetaBuildFrame =
      MetaBuildFrame(
        depth = depth,
        evaluator = evaluator,
        evalWatched = evalWatched,
        sharedFrame = RunnerSharedState.Frame(moduleWatched = Some(moduleWatched))
      )
  }

  /**
   * The frame at `requestedDepth` where user-visible tasks ran. Carries only
   * per-launcher data: the evaluator and the watched sets recorded during this
   * evaluation. There is at most one per launcher.
   */
  @internal
  case class FinalFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable]
  ) {
    def logged: Frame.Logged = Frame.build(Map.empty, evalWatched, moduleWatched, None, Nil)
  }

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

    private[RunnerLauncherState] def build(
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
