package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.daemon.internal.{EvaluatorApi, internal, PathRefApi, TaskApi}
import mill.api.internal.RootModule
import mill.api.daemon.Watchable
import mill.api.MillURLClassLoader
import upickle.{ReadWriter, macroRW}

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-launcher bootstrap state. Splits into meta-build frames and this launcher's
 * own [[RunnerState.FinalFrame]], if it reached the requested depth.
 *
 * - [[metaBuildFrames]] contains one [[RunnerState.MetaBuildFrame]] per meta-build
 *   depth processed in this run. Most of a meta-build frame's fields are
 *   deterministic in the meta-build source at that depth and are cached across
 *   concurrent launchers via the daemon-wide [[RunnerState.SharedFrames]]; the
 *   [[RunnerState.MetaBuildFrame.evaluator]] and
 *   [[RunnerState.MetaBuildFrame.metaBuildReadLease]] are per-launcher overlays
 *   and are stripped before publishing into [[RunnerState.SharedFrames]].
 * - [[finalFrame]] holds the launcher-unique state from running the user's tasks
 *   at `requestedDepth`. It has no shared data because nothing about it is
 *   deterministic enough to cache across launchers.
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
    metaBuildFrames: Map[Int, RunnerState.MetaBuildFrame] = Map.empty,
    finalFrame: Option[RunnerState.FinalFrame] = None
) extends Watching.Result
    with AutoCloseable {
  import RunnerState.*

  def addMetaBuildFrame(depth: Int, frame: MetaBuildFrame): RunnerState =
    copy(metaBuildFrames = metaBuildFrames.updated(depth, frame))

  def withFinalFrame(frame: FinalFrame): RunnerState = copy(finalFrame = Some(frame))

  def withError(err: String): RunnerState = copy(errorOpt = Some(err))

  def watched: Seq[Watchable] =
    metaBuildFrames.valuesIterator.flatMap(f => f.evalWatched ++ f.moduleWatched).toSeq ++
      finalFrame.iterator.flatMap(f => f.evalWatched ++ f.moduleWatched).toSeq ++
      bootstrapEvalWatched

  /** All evaluators across meta-build and final frames, in no particular order. */
  def allEvaluators: Seq[EvaluatorApi] =
    metaBuildFrames.valuesIterator.flatMap(_.evaluator).toSeq ++ finalFrame.iterator.map(_.evaluator)

  override def close(): Unit = {
    // Only release locking leases. Workers live in the process-level
    // SharedWorkerCache and must not be closed when a command finishes,
    // since they may be shared with concurrent or subsequent commands.
    metaBuildFrames.valuesIterator.foreach(_.metaBuildReadLease.foreach(_.close()))
  }
}

object RunnerState {
  def empty: RunnerState = RunnerState(None, None)

  /**
   * The state of a meta-build at a given depth.
   *
   * The "shared" fields ([[classLoaderOpt]], [[runClasspath]], [[compileOutput]],
   * [[codeSignatures]], [[buildOverrideFiles]], [[spanningInvalidationTree]],
   * [[workerCacheSummary]], [[evalWatched]], [[moduleWatched]]) are deterministic in
   * the meta-build source and are safe to share across concurrent launchers via
   * [[SharedFrames]]; writes are sequenced by the meta-build write lock in
   * [[mill.api.daemon.WorkspaceLocking]].
   *
   * The "per-launcher" fields ([[evaluator]] and [[metaBuildReadLease]]) are set
   * only in a launcher's [[RunnerState]]; [[SharedFrames.put]] strips them before
   * publishing so readers can't accidentally consume another launcher's state.
   *
   * If the meta-build failed to compile at this depth, all shared fields are
   * empty/[[None]]; we still carry the frame so [[evalWatched]] / [[moduleWatched]]
   * can drive a `--watch` re-run.
   */
  @internal
  case class MetaBuildFrame(
      classLoaderOpt: Option[MillURLClassLoader],
      runClasspath: Seq[PathRefApi],
      compileOutput: Option[PathRefApi],
      codeSignatures: Map[String, Int],
      buildOverrideFiles: Map[java.nio.file.Path, String],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String],
      workerCacheSummary: Map[String, Frame.WorkerInfo],
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      evaluator: Option[EvaluatorApi] = None,
      metaBuildReadLease: Option[mill.api.daemon.WorkspaceLocking.Lease] = None
  ) {
    def loggedData: Frame.Logged = Frame.loggedFor(
      workerCacheSummary,
      evalWatched,
      moduleWatched,
      classLoaderOpt.map(_.identity),
      runClasspath
    )
  }

  object MetaBuildFrame {

    /** A failed-compile frame: no shared data, just watches so `--watch` can retry. */
    def failed(
        evaluator: EvaluatorApi,
        evalWatched: Seq[Watchable],
        moduleWatched: Seq[Watchable]
    ): MetaBuildFrame = MetaBuildFrame(
      classLoaderOpt = None,
      runClasspath = Nil,
      compileOutput = None,
      codeSignatures = Map.empty,
      buildOverrideFiles = Map.empty,
      spanningInvalidationTree = None,
      workerCacheSummary = Map.empty,
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

  /**
   * Per-daemon-process store of meta-build frames keyed by depth. One instance lives
   * in [[mill.daemon.MillDaemonMain0]] (for daemon runs) or is created locally in
   * [[mill.daemon.MillNoDaemonMain0]] (for --no-daemon runs). Writes are sequenced
   * by the meta-build write lock at the corresponding depth.
   *
   * Per-launcher fields ([[MetaBuildFrame.evaluator]] and
   * [[MetaBuildFrame.metaBuildReadLease]]) are stripped on publish so no reader can
   * see another launcher's lease or evaluator.
   */
  final class SharedFrames {
    private val store = new ConcurrentHashMap[Int, MetaBuildFrame]()
    def get(depth: Int): Option[MetaBuildFrame] = Option(store.get(depth))
    def put(depth: Int, frame: MetaBuildFrame): Unit = {
      store.put(depth, frame.copy(evaluator = None, metaBuildReadLease = None))
      ()
    }
  }
}
