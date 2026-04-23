package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.daemon.internal.{EvaluatorApi, internal, PathRefApi, TaskApi}
import mill.api.internal.RootModule
import mill.api.daemon.Watchable
import mill.api.MillURLClassLoader
import upickle.{ReadWriter, macroRW}

/**
 * Bootstrap state. Doubles as both a per-launcher state (with [[finalFrame]] set
 * and per-launcher overlays attached to each [[RunnerState.MetaBuildFrame]]) and
 * the daemon-wide shared state held in an `AtomicReference[RunnerState]` (with
 * only the shared parts of meta-build frames populated and [[finalFrame]] empty).
 *
 * - [[metaBuildFrames]] contains one [[RunnerState.MetaBuildFrame]] per meta-build
 *   depth. Most of a meta-build frame's fields are deterministic in the
 *   meta-build source and safe to share across concurrent launchers; the
 *   [[RunnerState.MetaBuildFrame.evaluator]] and
 *   [[RunnerState.MetaBuildFrame.metaBuildReadLease]] fields are only populated
 *   in per-launcher states.
 * - [[finalFrame]] holds the launcher-unique state from running the user's tasks
 *   at `requestedDepth`. It has no shared data because nothing about it is
 *   deterministic enough to cache across launchers, and is never present in the
 *   daemon-wide shared state.
 * - [[moduleWatchedByDepth]] carries only the module-level watch snapshots that
 *   later runs need to decide whether a shared classloader must be refreshed.
 *
 * If evaluation fails before reaching the requested depth, [[errorOpt]] is set
 * and [[finalFrame]] is [[None]].
 */
@internal
case class RunnerState(
    bootstrapModuleOpt: Option[RootModule],
    errorOpt: Option[String],
    buildFile: Option[String] = None,
    // Watches captured during bootstrap module instantiation. Tracked separately because
    // a bootstrap failure produces no frames to carry them.
    bootstrapEvalWatched: Seq[Watchable] = Nil,
    metaBuildFrames: Seq[RunnerState.MetaBuildFrame] = Nil,
    finalFrame: Option[RunnerState.FinalFrame] = None,
    moduleWatchedByDepth: Map[Int, Seq[Watchable]] = Map.empty
) extends Watching.Result
    with AutoCloseable {
  import RunnerState.*

  /** Insert or replace the [[MetaBuildFrame]] at `frame.depth`. */
  def withMetaBuildFrame(frame: MetaBuildFrame): RunnerState =
    copy(metaBuildFrames = frame +: metaBuildFrames.filterNot(_.depth == frame.depth))

  def metaBuildFrameAt(depth: Int): Option[MetaBuildFrame] =
    metaBuildFrames.find(_.depth == depth)

  def withFinalFrame(frame: FinalFrame): RunnerState = copy(finalFrame = Some(frame))

  def withError(err: String): RunnerState = copy(errorOpt = Some(err))

  def withModuleWatched(depth: Int, watched: Seq[Watchable]): RunnerState =
    copy(moduleWatchedByDepth = moduleWatchedByDepth.updated(depth, watched))

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    metaBuildFrameAt(depth).map(_.moduleWatched)
      .orElse(finalFrame.filter(_.depth == depth).map(_.moduleWatched))
      .orElse(moduleWatchedByDepth.get(depth))

  def watched: Seq[Watchable] =
    metaBuildFrames.flatMap(f => f.evalWatched ++ f.moduleWatched) ++
      finalFrame.iterator.flatMap(f => f.evalWatched ++ f.moduleWatched).toSeq ++
      bootstrapEvalWatched

  /**
   * Evaluators ordered final-first, then meta-build frames from shallowest to
   * deepest. BSP script discovery and IDE generation walk this in order and
   * expect the workspace evaluator at `headOption`.
   */
  def allEvaluators: Seq[EvaluatorApi] =
    finalFrame.iterator.map(_.evaluator).toSeq ++
      metaBuildFrames.sortBy(_.depth).flatMap(_.evaluator)

  override def close(): Unit = {
    // Only release locking leases. Workers live in the process-level
    // SharedWorkerCache and must not be closed when a command finishes,
    // since they may be shared with concurrent or subsequent commands.
    metaBuildFrames.iterator.foreach(_.metaBuildReadLease.foreach(_.close()))
  }
}

object RunnerState {
  def empty: RunnerState = RunnerState(None, None)

  /**
   * The deterministic meta-build outputs at a given depth: classloader, compiled
   * classpath, code signatures, etc. Populated once a meta-build compiles
   * successfully and safe to share across concurrent launchers via the daemon-wide
   * `AtomicReference[RunnerState]`. Writes are sequenced by the meta-build write
   * lock in [[mill.api.daemon.WorkspaceLocking]].
   */
  @internal
  case class ReusableFrame(
      classLoader: MillURLClassLoader,
      runClasspath: Seq[PathRefApi],
      compileOutput: PathRefApi,
      codeSignatures: Map[String, Int],
      buildOverrideFiles: Map[java.nio.file.Path, String],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: String,
      workerCacheSummary: Map[String, Frame.WorkerInfo]
  )

  /**
   * A launcher's per-depth meta-build state.
   *
   * [[reusable]] references the shared [[ReusableFrame]] produced by the publishing
   * launcher (and visible to every other launcher at the same classloader identity).
   * It is [[None]] if the meta-build failed to compile at this depth; we still carry
   * the frame so [[evalWatched]] / [[moduleWatched]] can drive a `--watch` re-run.
   *
   * [[evaluator]] and [[metaBuildReadLease]] are per-launcher overlays and are
   * left empty in the daemon-wide shared state.
   */
  @internal
  case class MetaBuildFrame(
      depth: Int,
      reusable: Option[ReusableFrame],
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      evaluator: Option[EvaluatorApi] = None,
      metaBuildReadLease: Option[mill.api.daemon.WorkspaceLocking.Lease] = None
  ) {
    def classLoaderOpt: Option[MillURLClassLoader] = reusable.map(_.classLoader)
    def runClasspath: Seq[PathRefApi] = reusable.map(_.runClasspath).getOrElse(Nil)
    def compileOutput: Option[PathRefApi] = reusable.map(_.compileOutput)
    def codeSignatures: Map[String, Int] = reusable.map(_.codeSignatures).getOrElse(Map.empty)
    def buildOverrideFiles: Map[java.nio.file.Path, String] =
      reusable.map(_.buildOverrideFiles).getOrElse(Map.empty)
    def spanningInvalidationTree: Option[String] = reusable.map(_.spanningInvalidationTree)
    def workerCacheSummary: Map[String, Frame.WorkerInfo] =
      reusable.map(_.workerCacheSummary).getOrElse(Map.empty)

    def loggedData: Frame.Logged = Frame.loggedFor(
      workerCacheSummary,
      evalWatched,
      moduleWatched,
      classLoaderOpt.map(_.identity),
      runClasspath
    )
  }

  object MetaBuildFrame {

    /** A failed-compile frame: no [[ReusableFrame]], just watches so `--watch` can retry. */
    def failed(
        depth: Int,
        evaluator: EvaluatorApi,
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable]
    ): MetaBuildFrame = MetaBuildFrame(
      depth = depth,
      reusable = None,
      evalWatched = evalWatched,
      moduleWatched = moduleWatched,
      evaluator = Some(evaluator)
    )
  }

  /**
   * The frame at `requestedDepth` where user-visible tasks ran. Carries only
   * per-launcher data: the evaluator and the watched sets recorded during this
   * evaluation. [[depth]] is whatever `requestedDepth` resolved to for this run.
   */
  @internal
  case class FinalFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable]
  ) {
    def loggedData: Frame.Logged = Frame.loggedFor(Map.empty, evalWatched, moduleWatched, None, Nil)
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

    case class ClassLoaderInfo(identityHashCode: Int, paths: Seq[String], buildHash: Int)
    implicit val classLoaderInfoRw: ReadWriter[ClassLoaderInfo] = macroRW

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

    private[daemon] def loggedFor(
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
