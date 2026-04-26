package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.MillURLClassLoader
import mill.api.daemon.internal.{EvaluatorApi, PathRefApi, TaskApi}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.LauncherLocking
import upickle.{ReadWriter, macroRW}

/**
 * Per-launcher view of the current bootstrap/evaluation run.
 *
 * Holds launcher-owned resources that cannot be shared daemon-wide: active
 * evaluators, retained meta-build read leases that pin the shared frames the
 * launcher is using, the user-task evaluation, and the launcher session.
 */
case class RunnerLauncherState(
    errorOpt: Option[String] = None,
    buildFile: Option[String] = None,
    /**
     * Watch on the top-level `build.mill` file used to bootstrap. Kept as a
     * top-level field rather than as part of any meta frame because it is the
     * "we should restart at all" boundary watch — distinct from any meta-build
     * evaluator's own watches.
     */
    buildFileWatch: Option[Watchable] = None,
    /** Meta-build frames in deepest-first order (innermost first). */
    metaFrames: List[RunnerLauncherState.MetaFrame] = Nil,
    finalFrame: Option[RunnerLauncherState.FinalFrame] = None,
    sessionOpt: Option[LauncherSession] = None
) extends Watching.Result with AutoCloseable {
  import RunnerLauncherState.*

  def withMetaFrame(frame: MetaFrame): RunnerLauncherState =
    copy(metaFrames = frame +: metaFrames)
  def withFinalFrame(frame: FinalFrame): RunnerLauncherState =
    copy(finalFrame = Some(frame))
  def withError(err: String): RunnerLauncherState = copy(errorOpt = Some(err))
  def withSession(s: LauncherSession): RunnerLauncherState = copy(sessionOpt = Some(s))

  def metaFrameAt(depth: Int): Option[MetaFrame] = metaFrames.find(_.depth == depth)

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    metaFrameAt(depth).map(_.moduleWatched)
      .orElse(finalFrame.collect { case f if f.depth == depth => f.moduleWatched })

  def finalModuleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    finalFrame.collect { case f if f.depth == depth => f.moduleWatched }

  def watched: Seq[Watchable] =
    metaFrames.flatMap(f => f.evalWatched ++ f.moduleWatched) ++
      finalFrame.toList.flatMap(f => f.evalWatched ++ f.moduleWatched) ++
      buildFileWatch.toSeq

  def allEvaluators: Seq[EvaluatorApi] =
    finalFrame.toList.map(_.evaluator) ++ metaFrames.map(_.evaluator)

  override def close(): Unit = {
    val leases = metaFrames.flatMap(_.readLease)
    closeAll(allEvaluators.distinct ++ leases ++ sessionOpt.toSeq)
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
  val empty: RunnerLauncherState = RunnerLauncherState()

  /**
   * One meta-build level of an in-progress launcher evaluation. Holds a direct
   * reference to the [[RunnerSharedState.Frame]] instance the launcher pinned
   * with [[readLease]] (no read lease and a failed shared frame on a failed
   * bootstrap), so its classloader/runClasspath are read off [[sharedFrame]]
   * without duplication.
   */
  case class MetaFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      sharedFrame: RunnerSharedState.Frame,
      readLease: Option[LauncherLocking.Lease],
      spanningInvalidationTree: Option[String]
  ) {
    def evalWatched: Seq[Watchable] = sharedFrame.evalWatched
    def moduleWatched: Seq[Watchable] = sharedFrame.moduleWatched
    def classLoaderOpt: Option[MillURLClassLoader] = sharedFrame.reusable.map(_.classLoader)
    def runClasspath: Seq[PathRefApi] =
      sharedFrame.reusable.fold(Seq.empty[PathRefApi])(_.runClasspath)
  }

  /**
   * The user-task evaluation frame. Carries the task selectors that were run
   * so the next launcher invocation can short-circuit a re-run when the
   * inputs are unchanged.
   */
  case class FinalFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      tasksAndParams: Seq[String]
  )

  /**
   * Simplified representation of frame data, written to disk for debugging
   * and testing purposes (see `out/.../mill-runner-state.json`).
   */
  case class Logged(
      workerCache: Map[String, Logged.WorkerInfo],
      evalWatched: Seq[os.Path],
      moduleWatched: Seq[os.Path],
      classLoaderIdentity: Option[Int],
      runClasspath: Seq[(os.Path, Int)],
      runClasspathHash: Int
  )
  implicit val loggedRw: ReadWriter[Logged] = macroRW

  object Logged {
    case class WorkerInfo(identityHashCode: Int, inputHash: Int)
    implicit val workerInfoRw: ReadWriter[WorkerInfo] = macroRW

    private def summarizeWorkerCache(
        workerCache: collection.Map[String, (Int, Val, TaskApi[?])]
    ): Map[String, WorkerInfo] = workerCache.synchronized {
      workerCache.iterator.map { case (k, (i, v, _)) =>
        (k, WorkerInfo(System.identityHashCode(v), i))
      }.toMap
    }

    private def paths(ws: Seq[Watchable]): Seq[os.Path] =
      ws.collect { case Watchable.Path(p, _, _) => os.Path(p) }.distinct

    def fromMetaFrame(f: MetaFrame): Logged = Logged(
      workerCache = summarizeWorkerCache(f.evaluator.workerCache),
      evalWatched = paths(f.evalWatched),
      moduleWatched = paths(f.moduleWatched),
      classLoaderIdentity = f.classLoaderOpt.map(_.identity),
      runClasspath = f.runClasspath.map(p => os.Path(p.javaPath) -> p.sig),
      runClasspathHash = f.runClasspath.hashCode()
    )

    def fromFinalFrame(f: FinalFrame): Logged = Logged(
      workerCache = Map.empty,
      evalWatched = paths(f.evalWatched),
      moduleWatched = paths(f.moduleWatched),
      classLoaderIdentity = None,
      runClasspath = Nil,
      runClasspathHash = Seq.empty[PathRefApi].hashCode()
    )
  }
}
