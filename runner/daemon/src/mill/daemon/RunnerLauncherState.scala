package mill.daemon

import mill.api.Val
import mill.api.JsonFormatters.*
import mill.api.daemon.internal.{EvaluatorApi, internal, PathRefApi, TaskApi}
import mill.api.daemon.Watchable
import mill.api.daemon.internal.LauncherLocking
import upickle.{ReadWriter, macroRW}

@internal
case class RunnerLauncherState(
    errorOpt: Option[String] = None,
    buildFile: Option[String] = None,
    bootstrapEvalWatched: Seq[Watchable] = Nil,
    metaBuildFrames: List[RunnerLauncherState.MetaBuildFrame] = Nil,
    finalFrame: Option[RunnerLauncherState.FinalFrame] = None,
    session: LauncherSession = LauncherSession.Noop
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

  def withSession(s: LauncherSession): RunnerLauncherState =
    copy(session = s)

  def processedDepths: Int = metaBuildFrames.size + finalFrame.size

  def moduleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    metaBuildFrameAt(depth).flatMap(_.sharedFrame.moduleWatched)
      .orElse(finalFrame.collect { case frame if frame.depth == depth => frame.moduleWatched })

  def finalModuleWatchedAt(depth: Int): Option[Seq[Watchable]] =
    finalFrame.collect { case frame if frame.depth == depth => frame.moduleWatched }

  def watched: Seq[Watchable] =
    metaBuildFrames.flatMap(f => f.evalWatched ++ f.sharedFrame.moduleWatched.getOrElse(Nil)) ++
      finalFrame.toSeq.flatMap(f => f.evalWatched ++ f.moduleWatched) ++
      bootstrapEvalWatched

  def allEvaluators: Seq[EvaluatorApi] =
    finalFrame.map(_.evaluator).toSeq ++ metaBuildFrames.map(_.evaluator)

  override def close(): Unit = {
    // Keep the case-class data readable after close; only tear down the resources it points at.
    closeAll(
      allEvaluators.distinct ++
        metaBuildFrames.flatMap(_.metaBuildReadLease) ++
        Seq(session)
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

  @internal
  case class MetaBuildFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      sharedFrame: RunnerSharedState.Frame,
      metaBuildReadLease: Option[LauncherLocking.Lease] = None,
      spanningInvalidationTree: Option[String] = None
  ) {
    def logged: Frame.Logged = Frame.build(
      Frame.summarizeWorkerCache(evaluator.workerCache),
      evalWatched,
      sharedFrame.moduleWatched.getOrElse(Nil),
      sharedFrame.classLoaderOpt.map(_.identity),
      sharedFrame.runClasspath
    )
  }

  object MetaBuildFrame {

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

  @internal
  case class FinalFrame(
      depth: Int,
      evaluator: EvaluatorApi,
      evalWatched: Seq[Watchable],
      moduleWatched: Seq[Watchable],
      tasksAndParams: Seq[String]
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
